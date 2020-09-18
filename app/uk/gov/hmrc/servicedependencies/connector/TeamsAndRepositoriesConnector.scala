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

package uk.gov.hmrc.servicedependencies.connector

import com.google.inject.{Inject, Singleton}
import play.api.cache.AsyncCacheApi
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpReads}
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.model.{Repository, RepositoryInfo}
import uk.gov.hmrc.servicedependencies.model.Team

import scala.concurrent.{ExecutionContext, Future}

case class TeamsForServices(toMap: Map[String, Seq[String]]) {
  def getTeams(service: String): Seq[String] =
    toMap.getOrElse(service, Seq.empty)
}

@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  httpClient          : HttpClient,
  serviceConfiguration: ServiceDependenciesConfig,
  cache               : AsyncCacheApi
)(implicit ec: ExecutionContext
) {
  import HttpReads.Implicits._

  val teamsAndRepositoriesApiBase = serviceConfiguration.teamsAndRepositoriesServiceUrl

  implicit val rf  = Repository.format
  implicit val rif = RepositoryInfo.format
  implicit val tf  = Team.format

  def getRepository(repositoryName: String)(implicit hc: HeaderCarrier): Future[Option[Repository]] =
    httpClient.GET[Option[Repository]](s"$teamsAndRepositoriesApiBase/api/repositories/$repositoryName")

  def getTeamsForServices(implicit hc: HeaderCarrier): Future[TeamsForServices] =
    cache.getOrElseUpdate("teams-for-services", serviceConfiguration.teamsAndRepositoriesCacheExpiration){
      httpClient.GET[Map[String, Seq[String]]](s"$teamsAndRepositoriesApiBase/api/services?teamDetails=true")
        .map(TeamsForServices.apply)
    }

  def getAllRepositories(implicit hc: HeaderCarrier): Future[Seq[RepositoryInfo]] =
    httpClient.GET[Seq[RepositoryInfo]](s"$teamsAndRepositoriesApiBase/api/repositories")

  def getTeam(team: String)(implicit hc: HeaderCarrier): Future[Option[Map[String, Seq[String]]]] =
    httpClient.GET[Option[Map[String, Seq[String]]]](s"$teamsAndRepositoriesApiBase/api/teams/$team")

  def getTeamDetails(team: String)(implicit hc: HeaderCarrier): Future[Team] =
    httpClient.GET[Team](s"$teamsAndRepositoriesApiBase/api/teams_with_details/$team")
}
