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
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.controller.model.Dependencies
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag
import uk.gov.hmrc.servicedependencies.persistence.SlugInfoRepository
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamDependencyService @Inject()(
  teamsAndReposConnector  : TeamsAndRepositoriesConnector,
  slugInfoRepository      : SlugInfoRepository,
  githubDepLookup         : DependencyDataUpdatingService,
  serviceConfigsService   : ServiceConfigsService,
  slugDependenciesService : SlugDependenciesService)(implicit ec: ExecutionContext)  {

  def findAllDepsForTeam(team: String)(implicit hc: HeaderCarrier): Future[Seq[Dependencies]] =
    for {
      (teamDetails, githubDeps) <- ( teamsAndReposConnector.getTeamDetails(team)
                                   , githubDepLookup.getDependencyVersionsForAllRepositories()
                                   ).mapN { case (td, gh) => (td, gh) }
      libs                      =  teamDetails.libraries.map(l => githubDeps.find(_.repositoryName == l))
      services                  =  teamDetails.services.flatMap(s => githubDeps.find(_.repositoryName == s))
      updatedServices           <- Future.sequence(services.map(replaceServiceDeps))
      libsWithRules             <- Future.sequence(libs.flatten.map(serviceConfigsService.getDependenciesWithBobbyRules))
      allDeps                   =  libsWithRules ++ updatedServices
    } yield allDeps

  protected[service] def replaceServiceDeps(dep: Dependencies)  : Future[Dependencies] =
    for {
      slugDeps <- slugDependenciesService.curatedLibrariesOfSlug(dep.repositoryName, SlugInfoFlag.Latest)
      output   =  slugDeps.map(deps => dep.copy(libraryDependencies = deps)).getOrElse(dep)
    } yield output

}
