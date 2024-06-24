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
import play.api.libs.json.{Reads, __}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.model.Team

import scala.concurrent.{ExecutionContext, Future}


object TeamsAndRepositoriesConnector:
  import play.api.libs.functional.syntax.*
  import uk.gov.hmrc.servicedependencies.model.RepoType

  case class TeamsForServices(
    toMap: Map[String, Seq[String]]
  ):
    def getTeams(service: String): Seq[String] =
      toMap.getOrElse(service, Seq.empty)

  case class Repository(
    name      : String,
    teamNames : Seq[String],
    repoType  : RepoType,
    isArchived: Boolean
  )

  object Repository:
    val reads: Reads[Repository] =
      ( (__ \ "name"      ).format[String]
      ~ (__ \ "teamNames" ).format[Seq[String]]
      ~ (__ \ "repoType"  ).format[RepoType]
      ~ (__ \ "isArchived").format[Boolean]
      )(apply, r => Tuple.fromProductTyped(r))

  case class DeletedRepository(name: String)

  object DeletedRepository:
    val reads: Reads[DeletedRepository] =
      (__ \ "repoName")
        .read[String]
        .map:
          DeletedRepository.apply

@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  httpClientV2        : HttpClientV2,
  serviceConfiguration: ServiceDependenciesConfig,
  cache               : AsyncCacheApi
)(using
  ec: ExecutionContext
):
  import TeamsAndRepositoriesConnector.*
  import HttpReads.Implicits.*

  private val teamsAndRepositoriesApiBase = serviceConfiguration.teamsAndRepositoriesServiceUrl
  private given Reads[Repository]         = Repository.reads
  private given Reads[DeletedRepository]  = DeletedRepository.reads

  def getRepository(repositoryName: String)(using hc: HeaderCarrier): Future[Option[Repository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/repositories/$repositoryName")
      .execute[Option[Repository]]

  def getAllRepositories(archived: Option[Boolean])(using hc: HeaderCarrier): Future[Seq[Repository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/repositories?archived=$archived")
      .execute[Seq[Repository]]

  def getDecommissionedServices()(using hc: HeaderCarrier): Future[Seq[DeletedRepository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/decommissioned-repositories?repoType=service")
      .execute[Seq[DeletedRepository]]

  def getTeamsForServices()(using hc: HeaderCarrier): Future[TeamsForServices] =
    cache.getOrElseUpdate("teams-for-services", serviceConfiguration.teamsAndRepositoriesCacheExpiration):
      httpClientV2
        .get(url"$teamsAndRepositoriesApiBase/api/repository_teams")
        .execute[Map[String, Seq[String]]]
        .map(TeamsForServices.apply)

  def getTeam(team: String)(using hc: HeaderCarrier): Future[Team] =
    given Reads[Team] = Team.format
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/teams/$team?includeRepos=true")
      .execute[Team]
