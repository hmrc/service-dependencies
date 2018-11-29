/*
 * Copyright 2018 HM Revenue & Customs
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
import com.kenshoo.play.metrics.Metrics
import javax.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import uk.gov.hmrc.githubclient.{ExtendedContentsService, ExtendedGitHubClient, GithubClientMetrics, ReleaseService}
import uk.gov.hmrc.servicedependencies.Github
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.time.DateTimeUtils

@Singleton
class GithubConnector @Inject() (config: ServiceDependenciesConfig, metrics: Metrics) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def now = DateTimeUtils.now

  // Github client setup
  private lazy val client: ExtendedGitHubClient = ExtendedGitHubClient(config.githubApiOpenConfig.apiUrl, new GithubMetrics("github.open"))
    .setOAuth2Token(config.githubApiOpenConfig.key)
    .asInstanceOf[ExtendedGitHubClient]

  lazy val github: Github = new Github(new ReleaseService(client), new ExtendedContentsService(client))

  class GithubMetrics(override val metricName: String) extends GithubClientMetrics {
    lazy val registry = metrics.defaultRegistry
    override def increment(name: String): Unit =
      registry.counter(name).inc()
  }


  // github api helpers
  def findOtherDependencies(githubSearchResults: GithubSearchResults): Seq[MongoRepositoryDependency] = {
    githubSearchResults.others.foldLeft(Seq.empty[MongoRepositoryDependency]) {
      case (acc, (library, Some(currentVersion))) => acc :+ MongoRepositoryDependency(library, currentVersion)
      case (acc, (_, None)) => acc
    }
  }

  def findPluginDependencies(githubSearchResults: GithubSearchResults): Seq[MongoRepositoryDependency] = {
    githubSearchResults.sbtPlugins.foldLeft(Seq.empty[MongoRepositoryDependency]) {
      case (acc, (library, Some(currentVersion))) => acc :+ MongoRepositoryDependency(library, currentVersion)
      case (acc, (_, None)) => acc
    }
  }

  def findLatestLibrariesVersions(githubSearchResults: GithubSearchResults): Seq[MongoRepositoryDependency] = {
    githubSearchResults.libraries.foldLeft(Seq.empty[MongoRepositoryDependency]) {
      case (acc, (library, Some(currentVersion))) => acc :+ MongoRepositoryDependency(library, currentVersion)
      case (acc, (_, None)) => acc
    }
  }


  def findLatestSbtPluginVersion(sbtPluginConfig: SbtPluginConfig) : Option[SbtPluginVersion] = {
    github.findLatestVersion(sbtPluginConfig.name).map(v => SbtPluginVersion(sbtPluginConfig.name, Some(v)))
  }

  def findLatestLibraryVersion(lib: String): Option[LibraryVersion] = {
    github.findLatestVersion(lib).map(version => LibraryVersion(lib, Some(version)))
  }


  def buildDependencies(repo: RepositoryInfo, curatedDeps: CuratedDependencyConfig): Option[MongoRepositoryDependencies] = {

    github.findVersionsForMultipleArtifacts(repo.name, curatedDeps)
      .right
      .map(searchResults => {
      MongoRepositoryDependencies(
        repositoryName        = repo.name,
        libraryDependencies   = findLatestLibrariesVersions(searchResults),
        sbtPluginDependencies = findPluginDependencies(searchResults),
        otherDependencies     = findOtherDependencies(searchResults),
        updateDate            = now)})
    match {
      case Left(errorMessage) => {
        logger.error(s"Skipping dependencies update for ${repo.name}, reason: $errorMessage")
        None
      }
      case Right(results) => {
        logger.debug(s"Github search returned these results for ${repo.name}: $results")
        Some(results)
      }
    }
  }
}
