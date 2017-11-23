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
import uk.gov.hmrc.githubclient.{APIRateLimitExceededException, GithubApiClient}
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model.{GithubSearchResults, SbtPluginDependency, Version}
import uk.gov.hmrc.servicedependencies.util.{Max, VersionParser}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

abstract class Github {
  private val org = "HMRC"

  def gh: GithubApiClient

  def resolveTag(version: String): String =s"$tagPrefix$version"

  val tagPrefix: String

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def findVersionsForMultipleArtifacts(repoName: String, curatedDependencyConfig: CuratedDependencyConfig): GithubSearchResults = {

    GithubSearchResults(
      sbtPlugins = searchPluginSbtFileForMultipleArtifacts(repoName, curatedDependencyConfig.sbtPlugins),
      libraries = searchBuildFilesForMultipleArtifacts(repoName, curatedDependencyConfig.libraries),
      others = searchForOtherSpecifiedDependencies(repoName, curatedDependencyConfig.otherDependencies)
    )
  }

  protected def getLastGithubPushDate(repoName: String): Option[Date] = {
    Try(gh.repositoryService.getRepository(org, repoName).getPushedAt) match {
      case Success(date) => Some(date)
      case Failure(t) =>
        logger.error(s"getLastGithubPushDate failed for $repoName:", t)
        None
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

  private def searchBuildFilesForMultipleArtifacts(serviceName: String, artifacts: Seq[String]): Map[String, Option[Version]] = {

    @tailrec
    def searchRemainingBuildFiles(remainingBuildFiles: Seq[String]): Map[String, Option[Version]] = {
      remainingBuildFiles match {
        case filePath :: xs =>

          val versionsMap = performSearchForMultipleArtifacts(serviceName, filePath, parseFileForMultipleArtifacts(_, artifacts))
          if (!versionsMap.exists(_._2.isDefined))
            searchRemainingBuildFiles(xs)
          else
            versionsMap

        case Nil => Map.empty
      }
    }

    import collection.JavaConversions._
    val buildFilePaths: List[RepositoryContents] = gh.contentsService.getContents(repositoryId(serviceName, org), "project").toList
    val localPaths: List[String] = buildFilePaths.map(_.getPath).filter(_.endsWith(".scala")) :+ "build.sbt"

    searchRemainingBuildFiles(localPaths)
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
      case (ex: APIRateLimitExceededException) => throw ex
      case _: Throwable => Map.empty
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
    new IRepositoryIdProvider {
      val generateId: String = orgName + "/" + repoName
    }

  private def parseFileContents(response: RepositoryContents): String =
    new String(Base64.decodeBase64(response.getContent.replaceAll("\n", "")))
}
