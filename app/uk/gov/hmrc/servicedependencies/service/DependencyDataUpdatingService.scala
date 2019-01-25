/*
 * Copyright 2019 HM Revenue & Customs
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
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.LockFormats.Lock
import uk.gov.hmrc.servicedependencies.config.CuratedDependencyConfigProvider
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._
import uk.gov.hmrc.time.DateTimeUtils
import scala.concurrent.Future

@Singleton
class DependencyDataUpdatingService @Inject()(
  curatedDependencyConfigProvider        : CuratedDependencyConfigProvider,
  repositoryLibraryDependenciesRepository: RepositoryLibraryDependenciesRepository,
  libraryVersionRepository               : LibraryVersionRepository,
  sbtPluginVersionRepository             : SbtPluginVersionRepository,
  locksRepository                        : LocksRepository,
  mongoLocks                             : MongoLocks,
  dependenciesDataSource                 : DependenciesDataSource,
  slugParserJobsRepository               : SlugParserJobsRepository,
  slugInfoRepository                     : SlugInfoRepository
) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def now = DateTimeUtils.now

  def repositoryDependencyMongoLock: MongoLock = mongoLocks.repositoryDependencyMongoLock

  def libraryMongoLock: MongoLock = mongoLocks.libraryMongoLock

  def sbtPluginMongoLock: MongoLock = mongoLocks.sbtPluginMongoLock

  lazy val curatedDependencyConfig = curatedDependencyConfigProvider.curatedDependencyConfig

  def reloadLatestSbtPluginVersions(): Future[Seq[MongoSbtPluginVersion]] =
    runMongoUpdate(sbtPluginMongoLock) {
      val sbtPluginVersions = dependenciesDataSource.getLatestSbtPluginVersions(curatedDependencyConfig.sbtPlugins)

      Future.sequence(sbtPluginVersions.map { x =>
        sbtPluginVersionRepository.update(MongoSbtPluginVersion(x.sbtPluginName, x.version, now))
      })
    }

  def reloadLatestLibraryVersions(): Future[Seq[MongoLibraryVersion]] =
    runMongoUpdate(libraryMongoLock) {
      val latestLibraryVersions = dependenciesDataSource.getLatestLibrariesVersions(curatedDependencyConfig.libraries)

      Future.sequence(latestLibraryVersions.map { x =>
        libraryVersionRepository.update(MongoLibraryVersion(x.libraryName, x.version, now))
      })
    }

  def reloadCurrentDependenciesDataForAllRepositories(force: Boolean = false)(
    implicit hc: HeaderCarrier): Future[Seq[MongoRepositoryDependencies]] = {
    logger.debug(s"reloading current dependencies data for all repositories... (with force=$force)")
    runMongoUpdate(repositoryDependencyMongoLock) {
      for {
        currentDependencyEntries <- repositoryLibraryDependenciesRepository.getAllEntries
        libraryDependencies <- dependenciesDataSource.persistDependenciesForAllRepositories(
                                curatedDependencyConfig,
                                currentDependencyEntries,
                                force = force)
      } yield libraryDependencies

    }
  }

  private def runMongoUpdate[T](mongoLock: MongoLock)(f: => Future[Seq[T]]) =
    mongoLock.tryLock {
      logger.debug(s"Starting mongo update for ${mongoLock.lockId}")
      f
    }.map {
      case Some(r) =>
        logger.debug(s"mongo update completed ${mongoLock.lockId}")
        r
      case None =>
        logger.debug(s"Mongo is locked for ${mongoLock.lockId}... skipping update")
        Seq.empty
    }

  def getSbtPluginDependencyState(
      repositoryDependencies: MongoRepositoryDependencies,
      sbtPluginReferences   : Seq[MongoSbtPluginVersion]) =
    repositoryDependencies.sbtPluginDependencies.map { sbtPluginDependency =>
      val mayBeExternalSbtPlugin = curatedDependencyConfig.sbtPlugins
        .find(pluginConfig => pluginConfig.name == sbtPluginDependency.name && pluginConfig.isExternal())

      val latestVersion = mayBeExternalSbtPlugin
        .map(
          _.version.getOrElse(throw new RuntimeException(
            s"External sbt plugin ($mayBeExternalSbtPlugin) must specify the (latest) version")))
        .orElse(sbtPluginReferences.find(_.sbtPluginName == sbtPluginDependency.name).flatMap(_.version))

      Dependency(
        sbtPluginDependency.name,
        sbtPluginDependency.currentVersion,
        latestVersion,
        mayBeExternalSbtPlugin.isDefined
      )
    }

  def getDependencyVersionsForRepository(repositoryName: String): Future[Option[Dependencies]] =
    for {
      dependencies        <- repositoryLibraryDependenciesRepository.getForRepository(repositoryName)
      libraryReferences   <- libraryVersionRepository.getAllEntries
      sbtPluginReferences <- sbtPluginVersionRepository.getAllEntries
    } yield
      dependencies.map { dep =>
        Dependencies(
          repositoryName,
          dep.libraryDependencies.map(
            d =>
              Dependency(
                d.name,
                d.currentVersion,
                libraryReferences.find(mlv => mlv.libraryName == d.name).flatMap(_.version))),
          getSbtPluginDependencyState(dep, sbtPluginReferences),
          dep.otherDependencies.map(
            other =>
              Dependency(
                other.name,
                other.currentVersion,
                curatedDependencyConfig.otherDependencies.find(_.name == "sbt").flatMap(_.latestVersion))),
          dep.updateDate
        )
      }

  def getDependencyVersionsForAllRepositories(): Future[Seq[Dependencies]] =
    for {
      allDependencies     <- repositoryLibraryDependenciesRepository.getAllEntries
      libraryReferences   <- libraryVersionRepository.getAllEntries
      sbtPluginReferences <- sbtPluginVersionRepository.getAllEntries
    } yield
      allDependencies.map { dep =>
        Dependencies(
          dep.repositoryName,
          dep.libraryDependencies.map(
            d =>
              Dependency(
                d.name,
                d.currentVersion,
                libraryReferences.find(_.libraryName == d.name).flatMap(_.version))),
          getSbtPluginDependencyState(dep, sbtPluginReferences),
          dep.otherDependencies.map(
            other =>
              Dependency(
                other.name,
                other.currentVersion,
                curatedDependencyConfig.otherDependencies.find(_.name == "sbt").flatMap(_.latestVersion))),
          dep.updateDate
        )
      }

  def getAllRepositoriesDependencies(): Future[Seq[MongoRepositoryDependencies]] =
    repositoryLibraryDependenciesRepository.getAllEntries

  def dropCollection(collectionName: String) = collectionName match {
    case "repositoryLibraryDependencies" => repositoryLibraryDependenciesRepository.clearAllData
    case "libraryVersions"               => libraryVersionRepository.clearAllData
    case "sbtPluginVersions"             => sbtPluginVersionRepository.clearAllData
    case "locks"                         => locksRepository.clearAllData
    case "slugParserJobs"                => slugParserJobsRepository.clearAllData
    case other                           => throw new RuntimeException(s"dropping $other collection is not supported")
  }

  def locks(): Future[Seq[Lock]] =
    locksRepository.getAllEntries

  def clearUpdateDates =
    repositoryLibraryDependenciesRepository.clearUpdateDates

  def addSlugParserJob(newJob: NewSlugParserJob): Future[Boolean] =
    slugParserJobsRepository.add(newJob)

  def getSlugInfos(name: String, version: Option[String]): Future[Seq[SlugInfo]] =
    slugInfoRepository.getSlugInfos(name, version)

  def findServicesWithDependency(
    group: String, artefact: String, versionOp: VersionOp, version: Version): Future[Seq[ServiceDependency]] =
    slugInfoRepository.findServices(group, artefact).map { l =>
      versionOp match {
        case VersionOp.Gt => l.filter(_.depSemanticVersion >= version)
        case VersionOp.Lt => l.filter(_.depSemanticVersion <= version)
        case VersionOp.Eq => l.filter(_.depSemanticVersion == version)
      }
    }
}
