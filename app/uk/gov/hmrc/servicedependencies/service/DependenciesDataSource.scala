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

package uk.gov.hmrc.servicedependencies.service

import com.google.inject.{Inject, Singleton}
import com.kenshoo.play.metrics.Metrics
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import uk.gov.hmrc.githubclient.{APIRateLimitExceededException, GithubApiClient, GithubClientMetrics}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.connector.model.Repository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.presistence.RepositoryLibraryDependenciesRepository
import uk.gov.hmrc.servicedependencies.util.Max
import uk.gov.hmrc.time.DateTimeUtils

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Success}

@Singleton
class DependenciesDataSource @Inject()(teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
                                       config: ServiceDependenciesConfig,
                                       repositoryLibraryDependenciesRepository: RepositoryLibraryDependenciesRepository,
                                       metrics: Metrics) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def now = DateTimeUtils.now

  class GithubMetrics(override val metricName: String) extends GithubClientMetrics {

    lazy val registry = metrics.defaultRegistry

    override def increment(name: String): Unit = {
      registry.counter(name).inc()
    }
  }

  lazy val gitEnterpriseClient: GithubApiClient = GithubApiClient(config.githubApiEnterpriseConfig.apiUrl, config.githubApiEnterpriseConfig.key, new GithubMetrics("github.enterprise"))
  lazy val gitOpenClient: GithubApiClient = GithubApiClient(config.githubApiOpenConfig.apiUrl, config.githubApiOpenConfig.key, new GithubMetrics("github.open"))

  val buildFiles = Seq(
    "project/AppDependencies.scala",
    "project/MicroServiceBuild.scala",
    "project/FrontendBuild.scala",
    "project/StubServiceBuild.scala",
    "project/HmrcBuild.scala",
    "build.sbt"
  )


  object GithubOpen extends Github(buildFiles) {
    override val tagPrefix = "v"
    override lazy val gh = gitOpenClient
    override def toString: String = "GitHub.com"
  }

  object GithubEnterprise extends Github(buildFiles) {
    override val tagPrefix = "release/"
    override lazy val gh = gitEnterpriseClient
    override def resolveTag(version: String) = s"$tagPrefix$version"
    override def toString: String = "Github Enterprise"
  }

  lazy val githubEnterprise: Github = GithubEnterprise
  lazy val githubOpen: Github = GithubOpen

  protected[servicedependencies] lazy val githubs = Seq(githubOpen, githubEnterprise)

  def githubForRepository(r: Repository): Github =
    if (r.githubUrls.map(_.name).contains("github-com")) githubOpen else githubEnterprise


  def getLatestSbtPluginVersions(sbtPlugins: Seq[SbtPluginConfig]): Seq[SbtPluginVersion] = {

    def getLatestSbtPluginVersion(sbtPluginConfig: SbtPluginConfig): Option[Version] =
      Max.maxOf(githubs.map(gh => gh.findLatestVersion(sbtPluginConfig.name)))

    sbtPlugins.map(sbtPluginConfig =>
      sbtPluginConfig -> getLatestSbtPluginVersion(sbtPluginConfig)
    ).map {
      case (sbtPluginConfig, version) => SbtPluginVersion(sbtPluginConfig.name, version)
    }

  }

  def getLatestLibrariesVersions(libraries: Seq[String]): Seq[LibraryVersion] = {

    def getLatestLibraryVersion(lib: String): Option[Version] =
      Max.maxOf(githubs.map(gh => gh.findLatestVersion(lib)))

    libraries.map(lib =>
      lib -> getLatestLibraryVersion(lib)
    ).map {
      case (lib, version) => LibraryVersion(lib, version)
    }
  }


  def persistDependenciesForAllRepositories(curatedDependencyConfig: CuratedDependencyConfig,
                                            currentDependencyEntries: Seq[MongoRepositoryDependencies]
                                            )(implicit hc: HeaderCarrier): Future[Seq[MongoRepositoryDependencies]] = {

    val eventualAllRepos: Future[Seq[String]] = teamsAndRepositoriesConnector.getAllRepositories()

    def serialiseFutures[A, B](l: Iterable[A])(fn: A => Future[B]): Future[List[B]] =
      l.foldLeft(Future(List.empty[B])) {
        (previousFuture, next) ⇒
          for {
            previousResults ← previousFuture
            next ← fn(next)
          } yield previousResults :+ next
      }

    val orderedRepos: Future[Seq[Repository]] = eventualAllRepos.flatMap { repos =>
      val updatedLastOrdered = currentDependencyEntries.sortBy(_.updateDate.getMillis).map(_.repositoryName)
      val newRepos = repos.filterNot(r => currentDependencyEntries.exists(_.repositoryName == r))
      val allRepos = newRepos ++ updatedLastOrdered

      // I need this to avoid hitting teams-and-repositories with thousands of simultaneous calls.
      serialiseFutures(allRepos)(teamsAndRepositoriesConnector.getRepository)
    }

    @tailrec
    def getDependencies(remainingRepos: Seq[Repository], acc: Seq[MongoRepositoryDependencies]): Seq[MongoRepositoryDependencies] = {

      remainingRepos match {
        case repository :: xs =>
          val repoName = repository.name
          logger.info(s"Updating dependencies for: $repoName")
          val lastUpdated: DateTime = currentDependencyEntries.find(_.repositoryName == repoName).map(_.updateDate).getOrElse(new DateTime(0))
          if (lastUpdated.isAfter(repository.lastActive)) {
            logger.debug(s"No changes for repository ($repoName). Skipping....")
            getDependencies(xs, acc)
          } else {
            val errorOrDependencies: Either[APIRateLimitExceededException, DependenciesFromGitHub] = getDependenciesFromGitHub(repository, curatedDependencyConfig)

            errorOrDependencies match {
              case Left(error) =>
                logger.error(s"Something went wrong: ${error.getMessage}")
                // error (only rate limiting should be bubbled up to here) => short circuit
                logger.error("terminating current run because ===>", error)
                acc

              case Right(dependencies) =>
                val repositoryLibraryDependencies =
                  MongoRepositoryDependencies(
                    repositoryName = repoName,
                    libraryDependencies = dependencies.libraries,
                    sbtPluginDependencies = dependencies.sbtPlugins,
                    otherDependencies = dependencies.otherDependencies,
                    updateDate = now)
                repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies)
                getDependencies(xs, acc :+ repositoryLibraryDependencies)
            }
          }
        case Nil =>
          acc
      }
    }

    orderedRepos.map(r => getDependencies(r.toList, Nil)).andThen { case s =>
      s match {
        case Failure(x) => logger.error("Error!", x)
        case Success(g) =>
          logger.debug(s"finished ordering with ${g.mkString(", ")}")
      }
      s
    }

  }

  case class DependenciesFromGitHub(libraries: Seq[LibraryDependency],
                                    sbtPlugins: Seq[SbtPluginDependency],
                                    otherDependencies: Seq[OtherDependency])

  import cats.syntax.either._

  private def getDependenciesFromGitHub(repository: Repository,
                                        curatedDependencyConfig: CuratedDependencyConfig): Either[APIRateLimitExceededException, DependenciesFromGitHub] = {

    def getLibraryDependencies(githubSearchResults: GithubSearchResults) = githubSearchResults.libraries.foldLeft(Seq.empty[LibraryDependency]) {
      case (acc, (library, mayBeVersion)) =>
        mayBeVersion.fold(acc)(currentVersion => acc :+ LibraryDependency(library, currentVersion))
    }

    def getPluginDependencies(githubSearchResults: GithubSearchResults) = githubSearchResults.sbtPlugins.foldLeft(Seq.empty[SbtPluginDependency]) {
      case (acc, (plugin, mayBeVersion)) =>
        mayBeVersion.fold(acc)(currentVersion => acc :+ SbtPluginDependency(plugin, currentVersion))
    }

    def getOtherDependencies(githubSearchResults: GithubSearchResults) = githubSearchResults.others.foldLeft(Seq.empty[OtherDependency]) {
      case (acc, ("sbt", mayBeVersion)) =>
        mayBeVersion.fold(acc)(currentVersion => acc :+ OtherDependency("sbt", currentVersion))
    }

    // caching the rate limiting exception
    Either.catchOnly[APIRateLimitExceededException] {
      val searchResults = searchGithubsForArtifacts(repository, curatedDependencyConfig)
      DependenciesFromGitHub(
        getLibraryDependencies(searchResults),
        getPluginDependencies(searchResults),
        getOtherDependencies(searchResults)
      )
    }
  }


  private def searchGithubsForArtifacts(repository: Repository,
                                        curatedDependencyConfig: CuratedDependencyConfig): GithubSearchResults = {

    val github = githubForRepository(repository)

    logger.debug(s"searching ${github.gh} for dependencies of ${repository.name}")

    val result = github.findVersionsForMultipleArtifacts(repository.name, curatedDependencyConfig)
    logger.debug(s"github (${github.gh}) search returned these results for ${repository.name}: $result")
    result

  }

}
