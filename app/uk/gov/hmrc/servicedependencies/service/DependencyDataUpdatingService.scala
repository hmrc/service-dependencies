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
import org.slf4j.LoggerFactory
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ArtifactoryConnector, GithubConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.config.CuratedDependencyConfigProvider
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model.{MongoRepositoryDependencies, MongoRepositoryDependency, MongoDependencyVersion, ScalaVersion, Version}
import uk.gov.hmrc.servicedependencies.persistence.{DependencyVersionRepository, RepositoryLibraryDependenciesRepository}
import uk.gov.hmrc.servicedependencies.util.Max

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependencyDataUpdatingService @Inject()(
  curatedDependencyConfigProvider        : CuratedDependencyConfigProvider
, repositoryLibraryDependenciesRepository: RepositoryLibraryDependenciesRepository
, dependencyVersionRepository            : DependencyVersionRepository
, teamsAndRepositoriesConnector          : TeamsAndRepositoriesConnector
, artifactoryConnector                   : ArtifactoryConnector
, githubConnector                        : GithubConnector
)(implicit ec: ExecutionContext
) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def now: Instant = Instant.now()

  lazy val curatedDependencyConfig =
    curatedDependencyConfigProvider.curatedDependencyConfig

  def reloadLatestDependencyVersions(implicit hc: HeaderCarrier): Future[List[MongoDependencyVersion]] =
    for {
      res <- curatedDependencyConfig.allDependencies.traverse { config =>
               for {
                 optVersion   <- config.latestVersion
                                   .fold(
                                      artifactoryConnector.findLatestVersion(config.group, config.name)
                                        .map(vs => Max.maxOf(vs.values))
                                    )(v =>
                                      Future.successful(Some(v))
                                    )
                 optDbVersion <- optVersion.traverse { version =>
                                   val dbVersion  = MongoDependencyVersion(name = config.name, group = config.group, version = version, now)
                                   dependencyVersionRepository.update(dbVersion)
                                     .map(_ => dbVersion)
                                 }
               } yield optDbVersion
             }
    } yield res.flatten




  def reloadCurrentDependenciesDataForAllRepositories(
      force: Boolean = false
      )(implicit hc: HeaderCarrier
      ): Future[Seq[MongoRepositoryDependencies]] = {
    logger.debug(s"reloading current dependencies data for all repositories... (with force=$force)")
    for {
      currentDependencyEntries <- repositoryLibraryDependenciesRepository.getAllEntries
      repos                    <- teamsAndRepositoriesConnector.getAllRepositories
      libraryDependencies      <- repos.toList.traverse { repo =>
                                    buildMongoRepositoryDependencies(repo, curatedDependencyConfig, currentDependencyEntries, force)
                                      .traverse(repositoryLibraryDependenciesRepository.update)
                                  }.map(_.flatten)
    } yield libraryDependencies
  }

  def buildMongoRepositoryDependencies(
      repo                   : RepositoryInfo
    , curatedDependencyConfig: CuratedDependencyConfig
    , currentDeps            : Seq[MongoRepositoryDependencies]
    , force                  : Boolean
    ): Option[MongoRepositoryDependencies] = {

    val lastUpdated =
      currentDeps.find(_.repositoryName == repo.name)
        .map(_.updateDate)
        .getOrElse(Instant.EPOCH)

    if (force || !repo.lastUpdatedAt.isBefore(lastUpdated)) {
      logger.info(s"building repo for ${repo.name}")
      githubConnector.buildDependencies(repo, curatedDependencyConfig)
    } else {
      logger.debug(s"No changes for repository (${repo.name}). Skipping....")
      None
    }
  }

  private def toDependency(references: Seq[MongoDependencyVersion])(d: MongoRepositoryDependency): Dependency = {
    val optLatestVersion =
      references
        .find(ref => ref.name  == d.name &&
                     ref.group == d.group
             )
        .map(_.version)

    Dependency(
      name                = d.name
    , group               = d.group
    , currentVersion      = d.currentVersion
    , latestVersion       = optLatestVersion
    , bobbyRuleViolations = List.empty
    )
  }

  def getDependencyVersionsForRepository(repositoryName: String): Future[Option[Dependencies]] =
    for {
      dependencies       <- repositoryLibraryDependenciesRepository.getForRepository(repositoryName)
      dependencyVersions <- dependencyVersionRepository.getAllEntries
    } yield
      dependencies.map(dep =>
        Dependencies(
          repositoryName         = dep.repositoryName
        , libraryDependencies    = dep.libraryDependencies.map(toDependency(dependencyVersions))
        , sbtPluginsDependencies = dep.sbtPluginDependencies.map(toDependency(dependencyVersions))
        , otherDependencies      = dep.otherDependencies.map(toDependency(dependencyVersions))
        , lastUpdated            = dep.updateDate
        )
      )

  def getDependencyVersionsForAllRepositories: Future[Seq[Dependencies]] =
    for {
      allDependencies    <- repositoryLibraryDependenciesRepository.getAllEntries
      dependencyVersions <- dependencyVersionRepository.getAllEntries
    } yield
      allDependencies.map(dep =>
        Dependencies(
          repositoryName         = dep.repositoryName
        , libraryDependencies    = dep.libraryDependencies.map(toDependency(dependencyVersions))
        , sbtPluginsDependencies = dep.sbtPluginDependencies.map(toDependency(dependencyVersions))
        , otherDependencies      = dep.otherDependencies.map(toDependency(dependencyVersions))
        , lastUpdated            = dep.updateDate
        )
      )

  def getAllRepositoriesDependencies: Future[Seq[MongoRepositoryDependencies]] =
    repositoryLibraryDependenciesRepository.getAllEntries

  def dropCollection(collectionName: String) =
    collectionName match {
      case "repositoryLibraryDependencies" => repositoryLibraryDependenciesRepository.clearAllData
      case "dependencyVersions"            => dependencyVersionRepository.clearAllData
      case other                           => sys.error(s"dropping $other collection is not supported")
    }

  def clearUpdateDates =
    repositoryLibraryDependenciesRepository.clearUpdateDates
}
