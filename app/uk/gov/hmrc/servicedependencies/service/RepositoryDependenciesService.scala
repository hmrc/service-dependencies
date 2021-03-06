/*
 * Copyright 2021 HM Revenue & Customs
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
import play.api.Logging
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, MongoRepositoryDependency, MongoLatestVersion}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, RepositoryDependenciesRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RepositoryDependenciesService @Inject()(
  serviceDependenciesConfig       : ServiceDependenciesConfig
, repositoryDependenciesRepository: RepositoryDependenciesRepository
, latestVersionRepository         : LatestVersionRepository
, serviceConfigsConnector         : ServiceConfigsConnector
)(implicit ec: ExecutionContext
) extends Logging {

  lazy val curatedDependencyConfig =
    serviceDependenciesConfig.curatedDependencyConfig

  private def toDependency(
    latestVersions: Seq[MongoLatestVersion]
  , bobbyRules    : BobbyRules
  )(d: MongoRepositoryDependency
  ): Dependency = {
    val optLatestVersion =
      latestVersions
        .find(ref => ref.name  == d.name &&
                     ref.group == d.group
             )
        .map(_.version)

    Dependency(
      name                = d.name
    , group               = d.group
    , currentVersion      = d.currentVersion
    , latestVersion       = optLatestVersion
    , bobbyRuleViolations = bobbyRules.violationsFor(
                              group   = d.group
                            , name    = d.name
                            , version = d.currentVersion
                            )
    )
  }

  private def toDependencies(
    latestVersions: Seq[MongoLatestVersion]
  , bobbyRules    : BobbyRules
  )(ds: Seq[MongoRepositoryDependency]
  ): Seq[Dependency] =
    ds.map(toDependency(latestVersions, bobbyRules))
      .filter(dependency =>
          curatedDependencyConfig.allDependencies.exists(lib =>
            lib.name  == dependency.name &&
            lib.group == dependency.group
          ) ||
          dependency.bobbyRuleViolations.nonEmpty
        )

  def getDependencyVersionsForRepository(repositoryName: String): Future[Option[Dependencies]] =
    for {
      dependencies   <- repositoryDependenciesRepository.getForRepository(repositoryName)
      latestVersions <- latestVersionRepository.getAllEntries
      bobbyRules     <- serviceConfigsConnector.getBobbyRules
    } yield
      dependencies.map(dep =>
        Dependencies(
          repositoryName         = dep.repositoryName
        , libraryDependencies    = toDependencies(latestVersions, bobbyRules)(dep.libraryDependencies)
        , sbtPluginsDependencies = toDependencies(latestVersions, bobbyRules)(dep.sbtPluginDependencies)
        , otherDependencies      = toDependencies(latestVersions, bobbyRules)(dep.otherDependencies)
        , lastUpdated            = dep.updateDate
        )
      )

  def getDependencyVersionsForAllRepositories: Future[Seq[Dependencies]] =
    for {
      allDependencies <- repositoryDependenciesRepository.getAllEntries
      latestVersions  <- latestVersionRepository.getAllEntries
      bobbyRules      <- serviceConfigsConnector.getBobbyRules
    } yield
      allDependencies.map(dep =>
        Dependencies(
          repositoryName         = dep.repositoryName
        , libraryDependencies    = toDependencies(latestVersions, bobbyRules)(dep.libraryDependencies)
        , sbtPluginsDependencies = toDependencies(latestVersions, bobbyRules)(dep.sbtPluginDependencies)
        , otherDependencies      = toDependencies(latestVersions, bobbyRules)(dep.otherDependencies)
        , lastUpdated            = dep.updateDate
        )
      )
}
