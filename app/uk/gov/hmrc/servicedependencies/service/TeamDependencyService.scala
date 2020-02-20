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

import cats.implicits._
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, Dependencies}
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag
import uk.gov.hmrc.servicedependencies.persistence.SlugInfoRepository
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamDependencyService @Inject()(
  teamsAndReposConnector      : TeamsAndRepositoriesConnector
, slugInfoRepository          : SlugInfoRepository
, getMasterDependenciesService: GetMasterDependenciesService
, serviceConfigsConnector     : ServiceConfigsConnector
, slugDependenciesService     : SlugDependenciesService
)(implicit ec: ExecutionContext
) {

  def findAllDepsForTeam(teamName: String)(implicit hc: HeaderCarrier): Future[Seq[Dependencies]] =
    for {
      (teamDetails, githubDeps) <- ( teamsAndReposConnector.getTeamDetails(teamName)
                                   , getMasterDependenciesService.getDependencyVersionsForAllRepositories
                                   ).mapN { case (td, gh) => (td, gh) }
      libs                      =  teamDetails.libraries.flatMap(l => githubDeps.find(_.repositoryName == l))
      services                  =  teamDetails.services.flatMap(s => githubDeps.find(_.repositoryName == s))
      updatedServices           <- services.toList.traverse(replaceServiceDependencies)
    } yield libs ++ updatedServices

  protected[service] def replaceServiceDependencies(dependencies: Dependencies): Future[Dependencies] =
    for {
      optLibraryDependencies <- slugDependenciesService.curatedLibrariesOfSlug(dependencies.repositoryName, SlugInfoFlag.Latest)
      output                 =  optLibraryDependencies.map(libraryDependencies => dependencies.copy(libraryDependencies = libraryDependencies)).getOrElse(dependencies)
    } yield output

  def dependenciesOfSlugForTeam(
    teamName: String
  , flag    : SlugInfoFlag
  )(implicit hc: HeaderCarrier
  ): Future[Map[String, Seq[Dependency]]] =
    for {
      teamDetails <- teamsAndReposConnector.getTeamDetails(teamName)
      res         <- teamDetails.services.toList.traverse { serviceName =>
                       slugDependenciesService.curatedLibrariesOfSlug(serviceName, flag)
                         .map(_.map(serviceName -> _))
                     }
    } yield res.collect { case Some(kv) => kv }.toMap
}
