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

package uk.gov.hmrc.servicedependencies.connector

import com.google.inject.{Inject, Singleton}
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.model.{Repository, RepositoryInfo}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class Team(
  name: String,
  repos: Option[Map[String, Seq[String]]]
)

object Team {
  implicit val format = Json.format[Team]

  def normalisedName(name: String): String = name.toLowerCase.replaceAll(" ", "_")
}

case class TeamsForServices(toMap: Map[String, Seq[String]]) {
  def getTeams(service: String): Seq[String] =
    toMap.getOrElse(service, Seq.empty)
}

@Singleton
class TeamsAndRepositoriesConnector @Inject()(httpClient: HttpClient, serviceConfiguration: ServiceDependenciesConfig) {

  val teamsAndRepositoriesApiBase = serviceConfiguration.teamsAndRepositoriesServiceUrl

  implicit val formats = Repository.format
  implicit val repositoryInfoFormats = RepositoryInfo.format

  def getRepository(repositoryName: String)(implicit hc: HeaderCarrier): Future[Option[Repository]] =
    httpClient.GET[Option[Repository]](s"$teamsAndRepositoriesApiBase/api/repositories/$repositoryName")

  def getTeamsForServices()(implicit hc: HeaderCarrier): Future[TeamsForServices] =
    httpClient.GET[Map[String, Seq[String]]](s"$teamsAndRepositoriesApiBase/api/services?teamDetails=true")
      .map(TeamsForServices.apply)

  def getAllRepositories()(implicit hc: HeaderCarrier): Future[Seq[RepositoryInfo]] =
    httpClient.GET[Seq[RepositoryInfo]](s"$teamsAndRepositoriesApiBase/api/repositories")

  def getTeamsWithRepositories()(implicit hc: HeaderCarrier): Future[Seq[Team]] =
    httpClient.GET[Seq[Team]](s"$teamsAndRepositoriesApiBase/api/teams_with_repositories")

  def getTeam(team: String)(implicit hc: HeaderCarrier): Future[Option[Map[String, Seq[String]]]] =
    httpClient.GET[Option[Map[String, Seq[String]]]](s"$teamsAndRepositoriesApiBase/api/teams/$team")
}
