/*
 * Copyright 2023 HM Revenue & Customs
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
import play.api.libs.json.Format
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.model.Repository
import uk.gov.hmrc.servicedependencies.model.Team

import scala.concurrent.{ExecutionContext, Future}

case class TeamsForServices(toMap: Map[String, Seq[String]]) {
  def getTeams(service: String): Seq[String] =
    toMap.getOrElse(service, Seq.empty)
}

@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  httpClientV2        : HttpClientV2,
  serviceConfiguration: ServiceDependenciesConfig,
  cache               : AsyncCacheApi
)(implicit ec: ExecutionContext
) {
  import HttpReads.Implicits._

  val teamsAndRepositoriesApiBase = serviceConfiguration.teamsAndRepositoriesServiceUrl

  implicit val rf : Format[Repository] = Repository.format
  implicit val tf : Format[Team]       = Team.format

  def getRepository(repositoryName: String)(implicit hc: HeaderCarrier): Future[Option[Repository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/repositories/$repositoryName")
      .execute[Option[Repository]]

  def getAllRepositories(archived: Option[Boolean])(implicit hc: HeaderCarrier): Future[Seq[Repository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/repositories?archived=$archived")
      .execute[Seq[Repository]]

  def getTeamsForServices()(implicit hc: HeaderCarrier): Future[TeamsForServices] =
    cache.getOrElseUpdate("teams-for-services", serviceConfiguration.teamsAndRepositoriesCacheExpiration){
      httpClientV2
        .get(url"$teamsAndRepositoriesApiBase/api/repository_teams")
        .execute[Map[String, Seq[String]]]
        .map(TeamsForServices.apply)
    }

  def getTeam(team: String)(implicit hc: HeaderCarrier): Future[Team] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/teams/$team?includeRepos=true")
      .execute[Team]
}
