/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.connector

import com.google.inject.Provider
import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import org.apache.commons.codec.binary.Base64
import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.{IRepositoryIdProvider, RepositoryContents}
import uk.gov.hmrc.githubclient._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.model.Version
import uk.gov.hmrc.servicedependencies.util.VersionParser


case class GithubDependency(
    group  : String
  , name   : String
  , version: Version
  )

case class GithubSearchResults(
    sbtPlugins: Seq[GithubDependency]
  , libraries : Seq[GithubDependency]
  , others    : Seq[GithubDependency]
  )

case class GithubSearchError(message: String, throwable: Throwable)

class GithubConnector(
  releaseService : ReleaseService
, contentsService: ExtendedContentsService
) {

  private val org = "HMRC"

  def findVersionsForMultipleArtifacts(repoName: String): Either[GithubSearchError, GithubSearchResults] =
    try {
      Right(
        GithubSearchResults(
          sbtPlugins = searchPluginSbtFileForMultipleArtifacts(repoName)
        , libraries  = searchBuildFilesForMultipleArtifacts(repoName)
        , others     = searchForOtherSpecifiedDependencies(repoName)
        )
      )
    } catch {
      case ex: Throwable if ex.getMessage.toLowerCase.contains("api rate limit exceeded") =>
        throw APIRateLimitExceededException(ex)
      case ex: Throwable =>
        Left(GithubSearchError(s"Unable to find dependencies for $repoName. Reason: ${ex.getMessage}", ex))
    }

  private def searchBuildFilesForMultipleArtifacts(serviceName: String): Seq[GithubDependency] = {
    val filesInProjectDir =
      getContentsOrEmpty(serviceName, "project")
        .map(_.getPath)
        .filter(_.endsWith(".scala"))

    (filesInProjectDir :+ "build.sbt")
      .foldLeft(Seq.empty[GithubDependency]) { (acc, filePath) =>
        // if any results are found in file, assume all results are here, and terminate github scraping early
        if (acc.isEmpty)
          getContentsOrEmpty(serviceName, filePath)
            .headOption
            .fold(Seq.empty[GithubDependency])(parseFileForMultipleArtifacts)
        else acc
      }
  }

  private def getCurrentSbtVersion(repositoryName: String): Option[Version] = {
    val version = getContentsOrEmpty(repositoryName, "project/build.properties")
    if (version.isEmpty) None
    else VersionParser.parsePropertyFile(parseFileContents(version.head), "sbt.version")
  }

  private def searchForOtherSpecifiedDependencies(serviceName: String): Seq[GithubDependency] =
    getCurrentSbtVersion(serviceName)
      .map(v => GithubDependency(group = "org.scala-sbt", name = "sbt", version = v))
      .toSeq

  private def searchPluginSbtFileForMultipleArtifacts(serviceName: String): Seq[GithubDependency] =
    getContentsOrEmpty(serviceName, "project/plugins.sbt") // TODO what about other .sbt files?
      .headOption
      .fold(Seq.empty[GithubDependency])(parseFileForMultipleArtifacts)

  private def parseFileForMultipleArtifacts(response : RepositoryContents): Seq[GithubDependency] =
    VersionParser.parse(parseFileContents(response))

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


class GithubConnectorProvider @Inject()(
  config : ServiceDependenciesConfig
, metrics: Metrics
) extends Provider[GithubConnector] {

  class GithubMetrics(override val metricName: String) extends GithubClientMetrics {
    lazy val registry = metrics.defaultRegistry
    override def increment(name: String): Unit =
      registry.counter(name).inc()
  }

  // Github client setup
  private lazy val client: ExtendedGitHubClient =
    ExtendedGitHubClient(
        config.githubApiOpenConfig.apiUrl
      , new GithubMetrics("github.open")
      )
      .setOAuth2Token(config.githubApiOpenConfig.key)
      .asInstanceOf[ExtendedGitHubClient]

  override def get(): GithubConnector =
    new GithubConnector(
        new ReleaseService(client)
      , new ExtendedContentsService(client)
      )
}
