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
import uk.gov.hmrc.servicedependencies.config.CuratedDependencyConfigProvider
import uk.gov.hmrc.servicedependencies.connector.ArtifactoryConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependencyDataUpdatingService @Inject()(
  curatedDependencyConfigProvider        : CuratedDependencyConfigProvider
, repositoryLibraryDependenciesRepository: RepositoryLibraryDependenciesRepository
, libraryVersionRepository               : LibraryVersionRepository
, sbtPluginVersionRepository             : SbtPluginVersionRepository
, dependenciesDataSource                 : DependenciesDataSource
, artifactoryConnector                   : ArtifactoryConnector
)(implicit ec: ExecutionContext
) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def now: Instant = Instant.now()

  lazy val curatedDependencyConfig =
    curatedDependencyConfigProvider.curatedDependencyConfig

  // TODO skip if config.optVersion is defined? (it will be ignored later?)
  // or store the config.optVersion, then don't need to consult it later?
  def reloadLatestSbtPluginVersions(): Future[List[MongoSbtPluginVersion]] =
    curatedDependencyConfig.sbtPlugins.traverse { config =>
      for {
        optVersion <- artifactoryConnector.findLatestVersion(config.group, config.name)
        version    =  MongoSbtPluginVersion(name = config.name, group = config.group, version = optVersion, now)
        _          <- sbtPluginVersionRepository.update(version)
      } yield version
    }

  // TODO consolidate model to remove duplication
  def reloadLatestLibraryVersions(): Future[Seq[MongoLibraryVersion]] =
    curatedDependencyConfig.libraries.traverse { config =>
      for {
        optVersion <- artifactoryConnector.findLatestVersion(config.group, config.name)
        version    =  MongoLibraryVersion(name = config.name, group = config.group, version = optVersion, now)
        _          <- libraryVersionRepository.update(version)
      } yield version
    }

  def reloadCurrentDependenciesDataForAllRepositories(
      force: Boolean = false
      )(implicit hc: HeaderCarrier
      ): Future[Seq[MongoRepositoryDependencies]] = {
    logger.debug(s"reloading current dependencies data for all repositories... (with force=$force)")
    for {
      currentDependencyEntries <- repositoryLibraryDependenciesRepository.getAllEntries
      libraryDependencies      <- dependenciesDataSource.persistDependenciesForAllRepositories(
                                      curatedDependencyConfig
                                    , currentDependencyEntries
                                    , force = force
                                    )
    } yield libraryDependencies
  }

  private def toLibraryDependency(libraryReferences: Seq[MongoLibraryVersion])(d: MongoRepositoryDependency): Dependency = {
    val optConfig =
      curatedDependencyConfig.libraries
        .find(pluginConfig => pluginConfig.name  == d.name  &&
                              pluginConfig.group == d.group
             )

    val optRef =
      libraryReferences
        .find(libraryRef => libraryRef.name  == d.name &&
                            libraryRef.group == d.group
             )

    val latestVersion = optConfig.flatMap(_.latestVersion)
      .orElse(optRef.flatMap(_.version))

    Dependency(
      name                = d.name
    , group               = d.group
    , currentVersion      = d.currentVersion
    , latestVersion       = latestVersion
    , bobbyRuleViolations = List.empty
    )
  }

  private def toSbtPluginDependency(sbtPluginReferences: Seq[MongoSbtPluginVersion])(d: MongoRepositoryDependency): Dependency = {
    val optConfig =
      curatedDependencyConfig.sbtPlugins
        .find(pluginConfig => pluginConfig.name  == d.name  &&
                              pluginConfig.group == d.group
             )

    lazy val optRef =
      sbtPluginReferences
        .find(sbtPluginRef => sbtPluginRef.name  == d.name &&
                              sbtPluginRef.group == d.group
             )

    val latestVersion = optConfig.flatMap(_.latestVersion)
      .orElse(optRef.flatMap(_.version))

    Dependency(
      name                = d.name
    , group               = d.group
    , currentVersion      = d.currentVersion
    , latestVersion       = latestVersion
    , bobbyRuleViolations = List.empty
    )
  }

  private def toOtherDependency(d: MongoRepositoryDependency): Dependency = {

    val optConfig =
      curatedDependencyConfig.otherDependencies
        .find(otherRef => otherRef.name  == d.name &&
                          otherRef.group == d.group
             )


    val latestVersion = optConfig.flatMap(_.latestVersion)

    Dependency(
      name                = d.name
    , group               = d.group
    , currentVersion      = d.currentVersion
    , latestVersion       = latestVersion
    , bobbyRuleViolations = List.empty
    )
  }

  def getDependencyVersionsForRepository(repositoryName: String): Future[Option[Dependencies]] =
    for {
      dependencies        <- repositoryLibraryDependenciesRepository.getForRepository(repositoryName)
      libraryReferences   <- libraryVersionRepository.getAllEntries
      sbtPluginReferences <- sbtPluginVersionRepository.getAllEntries
    } yield
      dependencies.map(dep =>
        Dependencies(
          repositoryName         = dep.repositoryName
        , libraryDependencies    = dep.libraryDependencies.map(toLibraryDependency(libraryReferences))
        , sbtPluginsDependencies = dep.sbtPluginDependencies.map(toSbtPluginDependency(sbtPluginReferences))
        , otherDependencies      = dep.otherDependencies.map(toOtherDependency)
        , lastUpdated            = dep.updateDate
        )
      )

  def getDependencyVersionsForAllRepositories(): Future[Seq[Dependencies]] =
    for {
      allDependencies     <- repositoryLibraryDependenciesRepository.getAllEntries
      libraryReferences   <- libraryVersionRepository.getAllEntries
      sbtPluginReferences <- sbtPluginVersionRepository.getAllEntries
    } yield
      allDependencies.map(dep =>
        Dependencies(
          repositoryName         = dep.repositoryName
        , libraryDependencies    = dep.libraryDependencies.map(toLibraryDependency(libraryReferences))
        , sbtPluginsDependencies = dep.sbtPluginDependencies.map(toSbtPluginDependency(sbtPluginReferences))
        , otherDependencies      = dep.otherDependencies.map(toOtherDependency)
        , lastUpdated            = dep.updateDate
        )
      )

  def getAllRepositoriesDependencies(): Future[Seq[MongoRepositoryDependencies]] =
    repositoryLibraryDependenciesRepository.getAllEntries

  def dropCollection(collectionName: String) =
    collectionName match {
      case "repositoryLibraryDependencies" => repositoryLibraryDependenciesRepository.clearAllData
      case "libraryVersions"               => libraryVersionRepository.clearAllData
      case "sbtPluginVersions"             => sbtPluginVersionRepository.clearAllData
      case other                           => sys.error(s"dropping $other collection is not supported")
    }

  def clearUpdateDates =
    repositoryLibraryDependenciesRepository.clearUpdateDates
}
