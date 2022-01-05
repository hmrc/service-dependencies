/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.implicits._
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, MongoLatestVersion, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamDependencyService @Inject()(
  teamsAndReposConnector       : TeamsAndRepositoriesConnector
, slugInfoRepository           : SlugInfoRepository
, repositoryDependenciesService: RepositoryDependenciesService
, serviceConfigsConnector      : ServiceConfigsConnector
, slugDependenciesService      : SlugDependenciesService
, latestVersionRepository      : LatestVersionRepository
)(implicit ec: ExecutionContext
) {

  def findAllDepsForTeam(teamName: String)(implicit hc: HeaderCarrier): Future[Seq[Dependencies]] =
    for {
      (team, githubDeps) <- ( teamsAndReposConnector.getTeam(teamName)
                            , repositoryDependenciesService.getDependencyVersionsForAllRepositories
                            ).mapN { case (t, gh) => (t, gh) }
      libs               =  team.libraries.flatMap(l => githubDeps.find(_.repositoryName == l))
      services           =  team.services.flatMap(s => githubDeps.find(_.repositoryName == s))
      bobbyRules         <- serviceConfigsConnector.getBobbyRules
      latestVersions     <- latestVersionRepository.getAllEntries
      updatedServices    <- services.toList.traverse(dep => replaceServiceDependencies(dep, bobbyRules, latestVersions))
    } yield libs  ++ updatedServices

  protected[service] def replaceServiceDependencies(
    dependencies      : Dependencies,
    bobbyRules        : BobbyRules,
    latestVersions    : Seq[MongoLatestVersion]
  ): Future[Dependencies] =
    for {
      optLibraryDependencies <- slugDependenciesService.curatedLibrariesOfSlug(
                                  dependencies.repositoryName,
                                  SlugInfoFlag.Latest,
                                  bobbyRules,
                                  latestVersions
                                )
      output                 =  optLibraryDependencies.fold(dependencies)(libraryDependencies => dependencies.copy(libraryDependencies = libraryDependencies))
    } yield output

  def dependenciesOfSlugsForTeam(
    teamName: String
  , flag    : SlugInfoFlag
  )(implicit hc: HeaderCarrier
  ): Future[Map[String, Seq[Dependency]]] =
    for {
      team           <- teamsAndReposConnector.getTeam(teamName)
      latestVersions <- latestVersionRepository.getAllEntries
      bobbyRules     <- serviceConfigsConnector.getBobbyRules
      res            <- team.services.toList.traverse { serviceName =>
                          slugDependenciesService.curatedLibrariesOfSlug(serviceName, flag, bobbyRules, latestVersions)
                            .map(_.map(serviceName -> _))
                        }
    } yield res.collect { case Some(kv) => kv }.toMap
}
