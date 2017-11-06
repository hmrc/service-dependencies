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

import java.util.Date

import com.google.inject.{Inject, Singleton}
import com.kenshoo.play.metrics.Metrics
import org.slf4j.LoggerFactory
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import uk.gov.hmrc.githubclient.{APIRateLimitExceededException, GithubApiClient, GithubClientMetrics}
import uk.gov.hmrc.servicedependencies._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.Max

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Success}

@Singleton
class DependenciesDataSource @Inject()(teamsAndRepositoriesDataSource: TeamsAndRepositoriesDataSource,
                                       config: ServiceDependenciesConfig,
                                       timestampGenerator: TimestampGenerator,
                                       metrics: Metrics) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  class GithubMetrics(override val metricName: String) extends GithubClientMetrics {

    val registry = metrics.defaultRegistry

    override def increment(name: String): Unit = {
      registry.counter(name).inc()
    }
  }

  lazy val gitEnterpriseClient: GithubApiClient = GithubApiClient(config.githubApiEnterpriseConfig.apiUrl, config.githubApiEnterpriseConfig.key, new GithubMetrics("github.enterprise"))
  lazy val gitOpenClient: GithubApiClient = GithubApiClient(config.githubApiOpenConfig.apiUrl, config.githubApiOpenConfig.key, new GithubMetrics("github.open"))

  val buildFiles = Seq(
    "project/AppDependencies.scala",
    "build.sbt",
    "project/MicroServiceBuild.scala",
    "project/FrontendBuild.scala",
    "project/StubServiceBuild.scala",
    "project/HmrcBuild.scala"
  )


  object GithubOpen extends Github(buildFiles) {
    override val tagPrefix = "v"
    override val gh = gitOpenClient

    override def resolveTag(version: String) = s"$tagPrefix$version"
  }

  object GithubEnterprise extends Github(buildFiles) {
    override val tagPrefix = "release/"
    override val gh = gitEnterpriseClient

    override def resolveTag(version: String) = s"$tagPrefix$version"
  }

  lazy val githubEnterprise = GithubEnterprise
  lazy val githubOpen = GithubOpen
  protected[servicedependencies] lazy val githubs = Seq(githubOpen, githubEnterprise)


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


  private def isRepositoryUpdated(dependencies: DependenciesFromGitHub, maybeLastStoredGitUpdateDate: Option[Date]): Boolean = {
    (dependencies.lastGitUpdateDate, maybeLastStoredGitUpdateDate) match {
      case (Some(lastUpdateDate), Some(storedLastUpdateDate)) => lastUpdateDate.after(storedLastUpdateDate)
      case _ => true //!@  I think this should be false!!!
    }
  }


  def persistDependenciesForAllRepositories(curatedDependencyConfig: CuratedDependencyConfig,
                                            currentDependencyEntries: Seq[MongoRepositoryDependencies],
                                            persisterF: (MongoRepositoryDependencies) => Future[MongoRepositoryDependencies]): Future[Seq[MongoRepositoryDependencies]] = {

    val eventualAllRepos: Future[Seq[String]] = teamsAndRepositoriesDataSource.getAllRepositories()

    val orderedRepos: Future[Seq[String]] = eventualAllRepos.map { repos =>
      val updatedLastOrdered = currentDependencyEntries.sortBy(_.updateDate).map(_.repositoryName)
      val newRepos = repos.filterNot(r => currentDependencyEntries.exists(_.repositoryName == r))
      newRepos ++ updatedLastOrdered
    }

    @tailrec
    def getDependencies(remainingRepos: Seq[String], acc: Seq[MongoRepositoryDependencies]): Seq[MongoRepositoryDependencies] = {

      remainingRepos match {
        case repoName :: xs =>
          logger.info(s"getting dependencies for: $repoName")
          val maybeLastGitUpdateDate = currentDependencyEntries.find(_.repositoryName == repoName).flatMap(_.lastGitUpdateDate)
          val errorOrDependencies: Either[APIRateLimitExceededException, Option[DependenciesFromGitHub]] = getDependenciesFromGitHub(repoName, curatedDependencyConfig, maybeLastGitUpdateDate)

          if (errorOrDependencies.isLeft) {
            logger.error(s"Something went wrong: ${errorOrDependencies.left.get.getMessage}")
            // error (only rate limiting should be bubbled up to here) => short circuit
            logger.error("terminating current run because ===>", errorOrDependencies.left.get)
            acc
          } else {
            errorOrDependencies.right.get match {
              case None =>
                //!@persisterF(MongoRepositoryDependencies(repoName, Nil, Nil, Nil, maybeLastGitUpdateDate))
                logger.info(s"No dependencies found for: $repoName")
                getDependencies(xs, acc)
              case Some(dependencies) =>
                val repositoryLibraryDependencies =
                  MongoRepositoryDependencies(
                    repositoryName = repoName,
                    libraryDependencies = dependencies.libraries,
                    sbtPluginDependencies = dependencies.sbtPlugins,
                    otherDependencies = dependencies.otherDependencies,
                    lastGitUpdateDate = dependencies.lastGitUpdateDate,
                    updateDate = timestampGenerator.now)
                if (isRepositoryUpdated(dependencies, maybeLastGitUpdateDate))
                  persisterF(repositoryLibraryDependencies)
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
                                    otherDependencies: Seq[OtherDependency],
                                    lastGitUpdateDate: Option[Date])

  import cats.syntax.either._

  private def getDependenciesFromGitHub(repoName: String,
                                        curatedDependencyConfig: CuratedDependencyConfig,
                                        maybeLastCommitDate: Option[Date]): Either[APIRateLimitExceededException, Option[DependenciesFromGitHub]] = {

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
      searchGithubsForArtifacts(repoName, curatedDependencyConfig, maybeLastCommitDate).map { searchResults =>
        DependenciesFromGitHub(
          getLibraryDependencies(searchResults),
          getPluginDependencies(searchResults),
          getOtherDependencies(searchResults),
          searchResults.lastGitUpdateDate
        )
      }
    }
  }


  private def searchGithubsForArtifacts(repositoryName: String,
                                        curatedDependencyConfig: CuratedDependencyConfig,
                                        maybeLastCommitDate: Option[Date]): Option[GithubSearchResults] = {
    @tailrec
    def searchRemainingGitHubs(remainingGithubs: Seq[Github]): Option[GithubSearchResults] = {
      remainingGithubs match {
        case github :: xs =>

          logger.debug(s"searching ${github.gh} for dependencies of $repositoryName")
          val mayBeGithubSearchResults = github.findVersionsForMultipleArtifacts(repositoryName, curatedDependencyConfig, maybeLastCommitDate)

          if (mayBeGithubSearchResults.isEmpty) {
            logger.debug(s"github (${github.gh}) search returned no results for $repositoryName")
            searchRemainingGitHubs(xs)
          }
          else {
            logger.debug(s"github (${github.gh}) search returned these results for $repositoryName: ${mayBeGithubSearchResults.get}")
            mayBeGithubSearchResults
          }
        case Nil => None
      }
    }

    searchRemainingGitHubs(githubs)
  }

}
