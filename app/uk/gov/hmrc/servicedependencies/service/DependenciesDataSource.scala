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

package uk.gov.hmrc.servicedependencies.service

import com.google.inject.{Inject, Singleton}
import com.kenshoo.play.metrics.Metrics
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.githubclient._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.connector.model.Repository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.RepositoryLibraryDependenciesRepository
import uk.gov.hmrc.time.DateTimeUtils
import scala.concurrent.Future

@Singleton
class DependenciesDataSource @Inject()(
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  config: ServiceDependenciesConfig,
  repositoryLibraryDependenciesRepository: RepositoryLibraryDependenciesRepository,
  metrics: Metrics) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def now = DateTimeUtils.now

  class GithubMetrics(override val metricName: String) extends GithubClientMetrics {

    lazy val registry = metrics.defaultRegistry

    override def increment(name: String): Unit =
      registry.counter(name).inc()
  }

  private lazy val client: ExtendedGitHubClient = ExtendedGitHubClient(config.githubApiOpenConfig.apiUrl, new GithubMetrics("github.open"))
    .setOAuth2Token(config.githubApiOpenConfig.key)
    .asInstanceOf[ExtendedGitHubClient]

  lazy val github: Github = new Github(new ReleaseService(client), new ExtendedContentsService(client))

  def getLatestSbtPluginVersions(sbtPlugins: Seq[SbtPluginConfig]): Seq[SbtPluginVersion] = {

    def getLatestSbtPluginVersion(sbtPluginConfig: SbtPluginConfig): Option[Version] =
      github.findLatestVersion(sbtPluginConfig.name)

    sbtPlugins.map(sbtPluginConfig => sbtPluginConfig -> getLatestSbtPluginVersion(sbtPluginConfig)).map {
      case (sbtPluginConfig, version) => SbtPluginVersion(sbtPluginConfig.name, version)
    }

  }

  def getLatestLibrariesVersions(libraries: Seq[String]): Seq[LibraryVersion] = {

    def getLatestLibraryVersion(lib: String): Option[Version] =
      github.findLatestVersion(lib)

    libraries.map(lib => lib -> getLatestLibraryVersion(lib)).map {
      case (lib, version) => LibraryVersion(lib, version)
    }
  }

  def persistDependenciesForAllRepositories(
    curatedDependencyConfig: CuratedDependencyConfig,
    currentDependencyEntries: Seq[MongoRepositoryDependencies],
    force: Boolean = false)(implicit hc: HeaderCarrier): Future[Seq[MongoRepositoryDependencies]] = {

    val allRepositories: Future[Seq[String]] = teamsAndRepositoriesConnector.getAllRepositories()

    def serialiseFutures[A, B](l: Iterable[A])(fn: A => Future[B]): Future[Seq[B]] =
      l.foldLeft(Future(List.empty[B])) { (previousFuture, next) ⇒
        for {
          previousResults ← previousFuture
          next ← fn(next)
        } yield previousResults :+ next
      }

    def getRepoAndUpdate(repositoryName: String): Future[Option[MongoRepositoryDependencies]] =
      teamsAndRepositoriesConnector
        .getRepository(repositoryName)
        .map(maybeRepository => maybeRepository.flatMap(updateDependencies))

    def updateDependencies(repository: Repository): Option[MongoRepositoryDependencies] = {

      def getLibraryDependencies(githubSearchResults: GithubSearchResults) =
        githubSearchResults.libraries.foldLeft(Seq.empty[MongoRepositoryDependency]) {
          case (acc, (library, mayBeVersion)) =>
            mayBeVersion.fold(acc)(currentVersion => acc :+ MongoRepositoryDependency(library, currentVersion))
        }

      def getPluginDependencies(githubSearchResults: GithubSearchResults) =
        githubSearchResults.sbtPlugins.foldLeft(Seq.empty[MongoRepositoryDependency]) {
          case (acc, (plugin, mayBeVersion)) =>
            mayBeVersion.fold(acc)(currentVersion => acc :+ MongoRepositoryDependency(plugin, currentVersion))
        }

      def getOtherDependencies(githubSearchResults: GithubSearchResults) =
        githubSearchResults.others.foldLeft(Seq.empty[MongoRepositoryDependency]) {
          case (acc, ("sbt", mayBeVersion)) =>
            mayBeVersion.fold(acc)(currentVersion => acc :+ MongoRepositoryDependency("sbt", currentVersion))
        }

      val repoName = repository.name
      logger.info(s"Updating dependencies for: $repoName")
      val lastUpdated: DateTime =
        currentDependencyEntries.find(_.repositoryName == repoName).map(_.updateDate).getOrElse(new DateTime(0))
      if (!force && lastUpdated.isAfter(repository.lastActive)) {
        logger.debug(s"No changes for repository ($repoName). Skipping....")
        None
      } else {
        logger.debug(s"searching GitHub for dependencies of ${repository.name}")

        val dependencies = github.findVersionsForMultipleArtifacts(repository.name, curatedDependencyConfig)

        dependencies match {
          case Left(errorMessage) =>
            logger.error(s"Skipping dependencies update for $repoName, reason: $errorMessage")
            None
          case Right(searchResults) =>
            logger.debug(s"Github search returned these results for ${repository.name}: $searchResults")

            val repositoryLibraryDependencies =
              MongoRepositoryDependencies(
                repositoryName        = repoName,
                libraryDependencies   = getLibraryDependencies(searchResults),
                sbtPluginDependencies = getPluginDependencies(searchResults),
                otherDependencies     = getOtherDependencies(searchResults),
                updateDate            = now
              )

            repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies)

            Some(repositoryLibraryDependencies)
        }

      }
    }

    allRepositories.flatMap(serialiseFutures(_)(getRepoAndUpdate).map(_.flatten))

  }

}
