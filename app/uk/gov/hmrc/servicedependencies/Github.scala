/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.servicedependencies

import java.util.Date

import org.apache.commons.codec.binary.Base64
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.{IRepositoryIdProvider, RepositoryContents}
import org.slf4j.LoggerFactory
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model.{GithubSearchResults, SbtPluginDependency, Version}
import uk.gov.hmrc.servicedependencies.util.{Max, VersionParser}

import scala.annotation.tailrec
import scala.util.Try

abstract class Github(val buildFilePaths: Seq[String]) {
  private val org = "HMRC"

  def gh: GithubApiClient
  def resolveTag(version: String): String
  val tagPrefix: String

  lazy val logger = LoggerFactory.getLogger(this.getClass)


  def findVersionsForMultipleArtifacts(repoName: String,
                                       curatedDependencyConfig: CuratedDependencyConfig,
                                       storedLastUpdateDateO:Option[Date]): Option[GithubSearchResults] = {

    def performGithubSearch(maybeLastGitUpdateDate: Option[Date] ) = {
      Some(GithubSearchResults(
        sbtPlugins = searchPluginSbtFileForMultipleArtifacts(repoName, curatedDependencyConfig.sbtPlugins),
        libraries = searchBuildFilesForMultipleArtifacts(repoName, curatedDependencyConfig.libraries),
        others = searchForOtherSpecifiedDependencies(repoName, curatedDependencyConfig.otherDependencies),
        lastGitUpdateDate = maybeLastGitUpdateDate))
    }


    val maybeLastGitUpdateDate: Option[Date] = getLastGithubPushDate(repoName)

    maybeLastGitUpdateDate.fold(Option.empty[GithubSearchResults]) { lastGitUpdate =>
      storedLastUpdateDateO.fold {
        logger.info(s"No previous record for repository ($repoName) detected in database. processing...")
        performGithubSearch(maybeLastGitUpdateDate)
      } { storedLastUpdateDate =>
        if(lastGitUpdate.after(storedLastUpdateDate)) {
          logger.info(s"Changes to repository ($repoName) detected. processing...")
          performGithubSearch(maybeLastGitUpdateDate)
        } else {
          logger.info(s"No changes for repository ($repoName). Skipping....")
          Some(GithubSearchResults(sbtPlugins = Map.empty, libraries = Map.empty, others = Map.empty, lastGitUpdateDate = maybeLastGitUpdateDate))
        }
      }
    }


  }

  protected def getLastGithubPushDate(repoName: String): Option[Date] = {
    Try(gh.repositoryService.getRepository(org, repoName).getPushedAt).toOption
  }

  def findArtifactVersion(serviceName: String, artifact: String, versionOption: Option[String]): Option[Version] = {
    versionOption match {
      case Some(version) =>
        val result = searchPluginSbtFile(serviceName, artifact, version)
        if (result.nonEmpty) result
        else searchBuildFiles(serviceName, artifact, version)
      case None => None
    }
  }

  def findLatestVersion(repoName: String): Option[Version] = {

    val allVersions = Try(gh.releaseService.getTags(org, repoName).map(_.name)).recover {
      case ex: RequestException if ex.getStatus == 404 =>
        logger.info(s"Repository for $repoName not found")
        Nil
    }.get

    val maybeVersions: Seq[Option[Version]] = allVersions.map { version =>
      VersionParser.parseReleaseVersion(tagPrefix, version)
    }
    Max.maxOf(maybeVersions)
  }


  private def searchBuildFiles(serviceName: String, artifact:String, version: String): Option[Version] = {
    @tailrec
    def searchRemainingBuildFiles(remainingFiles: Seq[String]): Option[Version] = {
      remainingFiles match {
        case filePath :: xs =>
          val maybeVersion = performSearch(serviceName, Some(version), filePath, parseBuildFile(_, artifact))
          maybeVersion match {
            case None =>
              searchRemainingBuildFiles(xs)
            case Some(v) => Some(v)
          }
        case Nil => None
      }
    }

    searchRemainingBuildFiles(buildFilePaths)
  }


  private def searchBuildFilesForMultipleArtifacts(serviceName: String,artifacts: Seq[String]): Map[String, Option[Version]] = {

    @tailrec
    def searchRemainingBuildFiles(remainingBuildFiles: Seq[String]): Map[String, Option[Version]] = {
      remainingBuildFiles match {
        case filePath :: xs =>

          val versionsMap = performSearchForMultipleArtifacts(serviceName, filePath, parseFileForMultipleArtifacts(_, artifacts))
          if(versionsMap.isEmpty)
            searchRemainingBuildFiles(xs)
          else
            versionsMap

        case Nil => Map.empty
      }
    }

    searchRemainingBuildFiles(buildFilePaths)
  }

  private def getCurrentSbtVersion(repositoryName: String): Option[Version] =
    performSearch(repositoryName, None, "project/build.properties", rc => VersionParser.parsePropertyFile(parseFileContents(rc), "sbt.version"))

  private def searchForOtherSpecifiedDependencies(serviceName: String, otherDependencies: Seq[OtherDependencyConfig]): Map[String, Option[Version]] = {
    otherDependencies.find(_.name == "sbt").map(_ => Map("sbt" -> getCurrentSbtVersion(serviceName))).getOrElse(Map.empty)
  }

  private def searchPluginSbtFileForMultipleArtifacts(serviceName: String, sbtPluginConfigs: Seq[SbtPluginConfig]): Map[String, Option[Version]] =
    performSearchForMultipleArtifacts(serviceName, "project/plugins.sbt", parseFileForMultipleArtifacts(_, sbtPluginConfigs.map(_.name)))

  private def parseFileForMultipleArtifacts(response: RepositoryContents, artifacts: Seq[String]): Map[String, Option[Version]] = {
    VersionParser.parse(parseFileContents(response), artifacts)
  }

  private def performSearchForMultipleArtifacts(repoName: String,
                                                filePath: String,
                                                transformF: (RepositoryContents) => Map[String, Option[Version]]): Map[String, Option[Version]] =
    try {
      val results = gh.contentsService.getContents(repositoryId(repoName, org), filePath)
      if (results.isEmpty) Map.empty
      else transformF(results.get(0))
    }
    catch {
      case (ex: RequestException)  =>
        if (ex.getMessage.toLowerCase().contains("rate limit exceeded"))
          throw ex
        else {
          Map.empty
        }
    }


  private def searchPluginSbtFile(serviceName: String, artifact: String, version: String) =
      performSearch(serviceName, Some(version), "project/plugins.sbt", parsePluginsSbtFile(_, artifact))

  private def parseBuildFile(response: RepositoryContents, artifact: String): Option[Version] =
    VersionParser.parse(parseFileContents(response), artifact)

  private def parsePluginsSbtFile(response: RepositoryContents, artifact: String): Option[Version] =
    VersionParser.parse(parseFileContents(response), artifact)

  private def performSearch(serviceName: String, mayBeVersion: Option[String], filePath: String, transformF: (RepositoryContents) => Option[Version]): Option[Version] =
    try {
      val results = mayBeVersion.fold(gh.contentsService.getContents(repositoryId(serviceName, "HMRC"), filePath)) { version =>
          gh.contentsService.getContents(repositoryId(serviceName, "HMRC"), filePath, resolveTag(version))
        }
      if (results.isEmpty) None
      else transformF(results.get(0))
    }
    catch {
      case (ex: RequestException) => None
    }


  private def repositoryId(repoName: String, orgName: String): IRepositoryIdProvider =
    new IRepositoryIdProvider { val generateId: String = orgName + "/" + repoName }

  private def parseFileContents(response: RepositoryContents): String =
    new String(Base64.decodeBase64(response.getContent.replaceAll("\n", "")))
}
