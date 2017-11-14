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
import uk.gov.hmrc.githubclient.{GithubApiClient, GithubClientMetrics}
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

import scala.concurrent.Future

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

    val allRepositories: Future[Seq[String]] = teamsAndRepositoriesConnector.getAllRepositories()

    def serialiseFutures[A, B](l: Iterable[A])(fn: A => Future[B]): Future[Seq[B]] =
      l.foldLeft(Future(List.empty[B])) {
        (previousFuture, next) ⇒
          for {
            previousResults ← previousFuture
            next ← fn(next)
          } yield previousResults :+ next
      }

    def getRepoAndUpdate(repositoryName: String): Future[Option[MongoRepositoryDependencies]] = {
      teamsAndRepositoriesConnector.getRepository(repositoryName).map(maybeRepository => maybeRepository.flatMap(updateDependencies))
    }

    def updateDependencies(repository: Repository): Option[MongoRepositoryDependencies] = {
      val repoName = repository.name
      logger.info(s"Updating dependencies for: $repoName")
      val lastUpdated: DateTime = currentDependencyEntries.find(_.repositoryName == repoName).map(_.updateDate).getOrElse(new DateTime(0))
      if (lastUpdated.isAfter(repository.lastActive)) {
        logger.debug(s"No changes for repository ($repoName). Skipping....")
        None
      } else {
        val dependencies: DependenciesFromGitHub = getDependenciesFromGitHub(repository, curatedDependencyConfig)

        val repositoryLibraryDependencies =
          MongoRepositoryDependencies(
            repositoryName = repoName,
            libraryDependencies = dependencies.libraries,
            sbtPluginDependencies = dependencies.sbtPlugins,
            otherDependencies = dependencies.otherDependencies,
            updateDate = now)
        repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies)
        Some(repositoryLibraryDependencies)

      }
    }

    allRepositories.flatMap(serialiseFutures(_)(getRepoAndUpdate).map(_.flatten))

  }

  case class DependenciesFromGitHub(libraries: Seq[LibraryDependency],
                                    sbtPlugins: Seq[SbtPluginDependency],
                                    otherDependencies: Seq[OtherDependency])

  private def getDependenciesFromGitHub(repository: Repository,
                                        curatedDependencyConfig: CuratedDependencyConfig): DependenciesFromGitHub = {

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
    val searchResults = searchGithubsForArtifacts(repository, curatedDependencyConfig)
    DependenciesFromGitHub(
      getLibraryDependencies(searchResults),
      getPluginDependencies(searchResults),
      getOtherDependencies(searchResults)
    )
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
