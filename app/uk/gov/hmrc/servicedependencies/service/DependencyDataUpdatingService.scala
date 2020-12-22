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

package uk.gov.hmrc.servicedependencies.service

import java.time.Instant

import cats.implicits._
import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.{ArtifactoryConnector, GithubConnector, GithubDependency, GithubSearchResults, ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.model.{MongoLatestVersion, MongoRepositoryDependencies, MongoRepositoryDependency}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, RepositoryDependenciesRepository}
import uk.gov.hmrc.servicedependencies.util.Max

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependencyDataUpdatingService @Inject()(
  serviceDependenciesConfig       : ServiceDependenciesConfig
, repositoryDependenciesRepository: RepositoryDependenciesRepository
, latestVersionRepository         : LatestVersionRepository
, teamsAndRepositoriesConnector   : TeamsAndRepositoriesConnector
, artifactoryConnector            : ArtifactoryConnector
, githubConnector                 : GithubConnector
, serviceConfigsConnector         : ServiceConfigsConnector
)(implicit ec: ExecutionContext
) extends Logging {

  def now: Instant = Instant.now()

  lazy val curatedDependencyConfig =
    serviceDependenciesConfig.curatedDependencyConfig

  def reloadLatestVersions(): Future[List[MongoLatestVersion]] =
    curatedDependencyConfig.allDependencies
      .foldLeftM[Future, List[MongoLatestVersion]](List.empty) {
        case (acc, config) =>
          (for {
            optVersion <- config.latestVersion
                           .fold(
                             artifactoryConnector
                               .findLatestVersion(config.group, config.name)
                               .map(vs => Max.maxOf(vs.values))
                           )(v => Future.successful(Some(v)))
            optDbVersion <- optVersion.traverse { version =>
                             val dbVersion =
                               MongoLatestVersion(name = config.name, group = config.group, version = version, now)
                             latestVersionRepository
                               .update(dbVersion)
                               .map(_ => dbVersion)
                           }
          } yield optDbVersion).map(acc ++ _)
      }

  def reloadCurrentDependenciesDataForAllRepositories(implicit hc: HeaderCarrier): Future[Seq[MongoRepositoryDependencies]] = {
    logger.debug(s"reloading current dependencies data for all repositories...")
    for {
      currentDependencyEntries <- repositoryDependenciesRepository.getAllEntries
      repos                    <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false))
      libraryDependencies      <- repos.toList.traverse { repo =>
                                    buildMongoRepositoryDependencies(repo, currentDependencyEntries)
                                      .traverse(repositoryDependenciesRepository.update)
                                  }.map(_.flatten)
    } yield libraryDependencies
  }

  private def buildMongoRepositoryDependencies(
      repo        : RepositoryInfo
    , currentDeps : Seq[MongoRepositoryDependencies]
    ): Option[MongoRepositoryDependencies] = {

    val lastUpdated =
      currentDeps.find(_.repositoryName == repo.name)
        .map(_.updateDate)
        .getOrElse(Instant.EPOCH)

    if (!repo.lastUpdatedAt.isBefore(lastUpdated)) {
      logger.info(s"building repo for ${repo.name}")
      githubConnector.findVersionsForMultipleArtifacts(repo.name) match {
        case Left(errorMessage) =>
          logger.error(s"Skipping dependencies update for ${repo.name}, reason: $errorMessage")
          None
        case Right(githubSearchResults) =>
          val results = toMongoRepositoryDependencies(repo, githubSearchResults)
          logger.debug(s"Github search returned these results for ${repo.name}: $results")
          Some(results)
      }
    } else {
      logger.debug(s"No changes for repository (${repo.name}). Skipping....")
      None
    }
  }

  private def toMongoRepositoryDependencies(
    repo               : RepositoryInfo
  , githubSearchResults: GithubSearchResults
  ): MongoRepositoryDependencies = {
    def toMongoRepositoryDependencies(results: Seq[GithubDependency]): Seq[MongoRepositoryDependency] =
      results
        .map(d => MongoRepositoryDependency(name = d.name, group = d.group, currentVersion = d.version))

    MongoRepositoryDependencies(
      repositoryName        = repo.name
    , libraryDependencies   = toMongoRepositoryDependencies(githubSearchResults.libraries)
    , sbtPluginDependencies = toMongoRepositoryDependencies(githubSearchResults.sbtPlugins)
    , otherDependencies     = toMongoRepositoryDependencies(githubSearchResults.others)
    , updateDate            = now
    )
  }
}
