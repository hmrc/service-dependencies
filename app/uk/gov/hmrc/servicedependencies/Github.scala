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

import org.apache.commons.codec.binary.Base64
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.{IRepositoryIdProvider, RepositoryContents}
import org.slf4j.LoggerFactory
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.servicedependencies.model.Version
import uk.gov.hmrc.servicedependencies.util.{Max, VersionParser}

import scala.annotation.tailrec
import scala.util.Try

abstract class Github(buildFilePaths: Seq[String]) {
  private val org = "HMRC"

  def gh: GithubApiClient
  def resolveTag(version: String): String
  val tagPrefix: String

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def findVersionsForMultipleArtifacts(repoName: String, artifacts: Seq[String]): Map[String, Option[Version]] = {

//    val result = searchPluginSbtFileForMultipleArtifacts(repoName, artifacts)
////
//    if (result.nonEmpty)
//     result
//    else
       searchBuildFilesForMultipleArtifacts(repoName, artifacts)
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

  def findLatestLibraryVersion(libraryName: String): Option[Version] = {

    val allVersions = Try(gh.releaseService.getReleases(org, libraryName).map(_.tagName)).recover {
      case ex: RequestException if ex.getStatus == 404 =>
        logger.info(s"Library $libraryName not present")
        Nil
    }.get

    Max.maxOf(allVersions.map { version =>
      VersionParser.parseReleaseVersion(tagPrefix, version)
    })
  }

  private def searchBuildFiles(serviceName: String, artifact:String, version: String): Option[Version] = {
    @tailrec
    def searchRemainingBuildFiles(remainingFiles: Seq[String]): Option[Version] = {
      remainingFiles match {
        case filePath :: xs =>
          val maybeVersion = performSearch(serviceName, version, filePath, parseBuildFile(_, artifact))
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

  private def searchPluginSbtFileForMultipleArtifacts(serviceName: String, artifacts: Seq[String]) =
    performSearchForMultipleArtifacts(serviceName, "project/plugins.sbt", parseFileForMultipleArtifacts(_, artifacts))

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
      performSearch(serviceName, version, "project/plugins.sbt", parsePluginsSbtFile(_, artifact))

  private def parseBuildFile(response: RepositoryContents, artifact: String): Option[Version] =
    VersionParser.parse(parseFileContents(response), artifact)

  private def parsePluginsSbtFile(response: RepositoryContents, artifact: String): Option[Version] =
    VersionParser.parse(parseFileContents(response), artifact)

  private def performSearch(serviceName: String, version: String, filePath: String, transformF: (RepositoryContents) => Option[Version]): Option[Version] =
    try {
      val results = gh.contentsService.getContents(repositoryId(serviceName, "HMRC"), filePath, resolveTag(version))
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
