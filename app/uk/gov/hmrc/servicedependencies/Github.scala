/*
 * Copyright 2020 HM Revenue & Customs
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

import com.google.inject.Provider
import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import org.apache.commons.codec.binary.Base64
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.{IRepositoryIdProvider, RepositoryContents}
import org.slf4j.LoggerFactory
import uk.gov.hmrc.githubclient._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, LibraryConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model.{GithubSearchResults, Version}
import uk.gov.hmrc.servicedependencies.util.{Max, VersionParser}

import scala.annotation.tailrec
import scala.util.Try

case class GithubSearchError(message: String, throwable: Throwable)

class Github(releaseService: ReleaseService, contentsService: ExtendedContentsService) {

  private val org = "HMRC"

  private lazy val logger = LoggerFactory.getLogger(this.getClass)

  def findVersionsForMultipleArtifacts(
    repoName: String,
    curatedDependencyConfig: CuratedDependencyConfig): Either[GithubSearchError, GithubSearchResults] =
    try {
      Right(
        GithubSearchResults(
          sbtPlugins = searchPluginSbtFileForMultipleArtifacts(repoName, curatedDependencyConfig.sbtPlugins)
        , libraries  = searchBuildFilesForMultipleArtifacts(repoName, curatedDependencyConfig.libraries)
        , others     = searchForOtherSpecifiedDependencies(repoName, curatedDependencyConfig.otherDependencies)
        )
      )
    } catch {
      case ex: Throwable if ex.getMessage.toLowerCase.contains("api rate limit exceeded") =>
        throw APIRateLimitExceededException(ex)
      case ex: Throwable =>
        Left(GithubSearchError(s"Unable to find dependencies for $repoName. Reason: ${ex.getMessage}", ex))
    }


  def findLatestVersion(repoName: String): Option[Version] = {

    val allVersions = Try(releaseService.getTags(org, repoName).map(_.name))
      .recover {
        case ex: RequestException if ex.getStatus == 404 =>
          logger.info(s"Repository for $repoName not found")
          Nil
      }.get

    val maybeVersions: Seq[Option[Version]] = allVersions.map(VersionParser.parseReleaseVersion)
    Max.maxOf(maybeVersions)
  }

  private def searchBuildFilesForMultipleArtifacts(
    serviceName: String
  , artifacts  : Seq[LibraryConfig]
  ): Map[(String, String), Option[Version]] = {

    @tailrec
    def searchRemainingBuildFiles(remainingBuildFiles: Seq[String]): Map[(String, String), Option[Version]] =
      remainingBuildFiles match {
        case filePath :: xs =>
          val versionsMap =
            getContentsOrEmpty(serviceName, filePath)
              .headOption
              .fold(Map.empty[(String, String), Option[Version]])(parseFileForMultipleArtifacts(_, artifacts.map(a => (a.name, a.group))))
          if (!versionsMap.exists(_._2.isDefined))
            searchRemainingBuildFiles(xs)
          else
            versionsMap

        case Nil => Map.empty
      }

    searchRemainingBuildFiles(performProjectDirectorySearch(serviceName) :+ "build.sbt")
  }

  private def getCurrentSbtVersion(repositoryName: String): Option[Version] = {
    val version = getContentsOrEmpty(repositoryName, "project/build.properties")
    if (version.isEmpty) None
    else VersionParser.parsePropertyFile(parseFileContents(version.head), "sbt.version")
  }

  private def searchForOtherSpecifiedDependencies(
    serviceName      : String
  , otherDependencies: Seq[OtherDependencyConfig]
  ): Map[(String, String), Option[Version]] =
    otherDependencies
      .find(d => d.name == "sbt" && d.group == "org.scala-sbt")
      .fold(Map.empty[(String, String), Option[Version]])(c => Map((c.name, c.group) -> getCurrentSbtVersion(serviceName)))

  private def searchPluginSbtFileForMultipleArtifacts(
    serviceName     : String
  , sbtPluginConfigs: Seq[SbtPluginConfig]
  ): Map[(String, String), Option[Version]] =
    getContentsOrEmpty(serviceName, "project/plugins.sbt")
      .headOption
      .fold(Map.empty[(String, String), Option[Version]])(parseFileForMultipleArtifacts(_, sbtPluginConfigs.map(c => (c.name, c.group))))

  private def parseFileForMultipleArtifacts(
    response : RepositoryContents
  , artifacts: Seq[(String, String)]
  ): Map[(String, String), Option[Version]] =
    VersionParser.parse(parseFileContents(response), artifacts)

  private def performProjectDirectorySearch(repoName: String): Seq[String] =
    getContentsOrEmpty(repoName, "project")
      .map(_.getPath)
      .filter(_.endsWith(".scala"))

  private def repositoryId(repoName: String, orgName: String): IRepositoryIdProvider =
    new IRepositoryIdProvider {
      val generateId: String = orgName + "/" + repoName
    }

  private def parseFileContents(response: RepositoryContents): String =
    new String(Base64.decodeBase64(response.getContent.replaceAll("\n", "")))

  private def getContentsOrEmpty(repoName: String, path: String): List[RepositoryContents] = {
    import scala.collection.JavaConverters._
    try {
      contentsService.getContents(repositoryId(repoName, org), path).asScala.toList
     } catch {
       case ex: RequestException if ex.getStatus == 404 => Nil
     }
  }

}


class GithubProvider @Inject() (config: ServiceDependenciesConfig, metrics: Metrics) extends Provider[Github] {

  class GithubMetrics(override val metricName: String) extends GithubClientMetrics {
    lazy val registry = metrics.defaultRegistry
    override def increment(name: String): Unit =
      registry.counter(name).inc()
  }

  // Github client setup
  private lazy val client: ExtendedGitHubClient = ExtendedGitHubClient(config.githubApiOpenConfig.apiUrl, new GithubMetrics("github.open"))
    .setOAuth2Token(config.githubApiOpenConfig.key)
    .asInstanceOf[ExtendedGitHubClient]

  override def get(): Github = {
    new Github(new ReleaseService(client), new ExtendedContentsService(client))
  }
}
