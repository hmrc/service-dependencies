/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.metrix.domain.MetricSource
import uk.gov.hmrc.servicedependencies.connector.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model.MongoRepositoryDependency
import uk.gov.hmrc.servicedependencies.persistence.RepositoryLibraryDependenciesRepository
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

case class TeamRepos(teamName: String, repos: Map[String, Seq[MongoRepositoryDependency]])

@Singleton
class RepositoryDependenciesSource @Inject()(
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  repositoryLibraryDependenciesRepository: RepositoryLibraryDependenciesRepository
) extends MetricSource {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    getTeamsLibraries.map(_.flatMap(collectTeamStats).toMap)

  def getTeamsLibraries: Future[Seq[TeamRepos]] =
    for {
      teamsWithRepositories <- teamsAndRepositoriesConnector.getTeamsWithRepositories()
      dependencies <- repositoryLibraryDependenciesRepository.getAllEntries
                       .map(repos =>
                         repos
                           .filterNot(_.repositoryName.endsWith("-history"))
                           .map(repoDependencies =>
                             repoDependencies.repositoryName -> (repoDependencies.libraryDependencies ++ repoDependencies.sbtPluginDependencies ++ repoDependencies.otherDependencies)))
    } yield
      teamsWithRepositories.map {
        case Team(name, Some(repos)) =>
          TeamRepos(
            teamName = Team.normalisedName(name),
            repos = dependencies.filter {
              case (repoName, _) =>
                val serviceRepos = repos.get("Service")
                serviceRepos.exists(_.contains(repoName))
            }.toMap
          )
        case Team(name, None) =>
          TeamRepos(name, Map.empty)
      }

  def collectTeamStats(teamRepos: TeamRepos): Map[String, Int] =
    teamRepos.repos.foldLeft(Map.empty[String, Int]) {
      case (acc, (repoName, deps)) =>
        acc ++ deps
          .map(dep =>
            s"teams.${teamRepos.teamName}.services.$repoName.libraries.${dep.name}.versions.${dep.currentVersion.normalise}" -> 1)
          .toMap ++ Map(s"teams.${teamRepos.teamName}.servicesCount" -> teamRepos.repos.size)

    }

}
