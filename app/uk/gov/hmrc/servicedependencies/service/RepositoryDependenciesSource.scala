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

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.metrix.MetricSource
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.model.{MongoRepositoryDependency, Team, Version}
import uk.gov.hmrc.servicedependencies.persistence.RepositoryDependenciesRepository

import scala.concurrent.{ExecutionContext, Future}

case class TeamRepos(
  teamName: String,
  repos   : Map[String, Seq[MongoRepositoryDependency]])

@Singleton
class RepositoryDependenciesSource @Inject()(
  teamsAndRepositoriesConnector   : TeamsAndRepositoriesConnector,
  repositoryDependenciesRepository: RepositoryDependenciesRepository
)(implicit ec: ExecutionContext
) extends MetricSource {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    getTeamsLibraries.map(_.flatMap(collectTeamStats).toMap)

  def getTeamsLibraries: Future[Seq[TeamRepos]] =
    for {
      teamsWithRepositories <- teamsAndRepositoriesConnector.getTeamsWithRepositories
      dependencies <- repositoryDependenciesRepository.getAllEntries
                       .map(_
                          .filterNot(_.repositoryName.endsWith("-history"))
                          .map(repoDependencies => repoDependencies.repositoryName -> repoDependencies.dependencies)
                       )
    } yield
      teamsWithRepositories.map { team =>
        TeamRepos(
          teamName = Team.normalisedName(team.name),
          repos = dependencies.filter {
            case (repoName, _) =>
              val serviceRepos = team.repos.get("Service")
              serviceRepos.exists(_.contains(repoName))
          }.toMap
        )
      }

  def collectTeamStats(teamRepos: TeamRepos): Map[String, Int] = {
    def normalise(v: Version) = s"${v.major}_${v.minor}_${v.patch}"
    Map(s"teams.${teamRepos.teamName}.servicesCount" -> teamRepos.repos.size) ++
      teamRepos.repos.toList.flatMap { case (repoName, deps) =>
        deps
          .map(dep => s"teams.${teamRepos.teamName}.services.$repoName.libraries.${dep.name}.versions.${normalise(dep.currentVersion)}" -> 1)
      }.toMap
  }
}
