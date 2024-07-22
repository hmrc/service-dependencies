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
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, UpstreamErrorResponse, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.model.RepoType

import scala.concurrent.{ExecutionContext, Future}

object TeamsAndRepositoriesConnector:
  import play.api.libs.functional.syntax.*

  case class Repository(
    name      : String
  , teamNames : Seq[String]
  , repoType  : RepoType
  , isArchived: Boolean
  )

  object Repository:
    val reads: Reads[Repository] =
      ( (__ \ "name"      ).read[String]
      ~ (__ \ "teamNames" ).read[Seq[String]]
      ~ (__ \ "repoType"  ).read[RepoType]
      ~ (__ \ "isArchived").read[Boolean]
      )(apply)

  case class DeletedRepository(name: String)

  object DeletedRepository:
    val reads: Reads[DeletedRepository] =
      (__ \ "repoName")
        .read[String]
        .map:
          DeletedRepository.apply

  case class Team(
    name: String
  )

  object Team:
    val reads: Reads[Team] =
      (__ \ "name")
        .read[String]
        .map(Team.apply)

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
  private given Reads[Team]               = Team.reads
  private given Reads[Repository]         = Repository.reads
  private given Reads[DeletedRepository]  = DeletedRepository.reads

  def checkTeamExists(teamName: String)(using hc: HeaderCarrier): Future[Team] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/teams")
      .execute[List[Team]]
      .flatMap(_.find(_.name == teamName).fold(Future.failed(UpstreamErrorResponse(s"Team: $teamName not found", 404)))(Future.successful))

  def getRepository(repositoryName: String)(using hc: HeaderCarrier): Future[Option[Repository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/repositories/$repositoryName")
      .execute[Option[Repository]]

  def getAllRepositories(
    archived: Option[Boolean]
  , teamName: Option[String]   = None
  , repoType: Option[RepoType] = None
  )(using hc: HeaderCarrier): Future[Seq[Repository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/repositories?archived=$archived&team=$teamName&repoType=${repoType.map(_.asString)}")
      .execute[Seq[Repository]]

  def getDecommissionedServices()(using hc: HeaderCarrier): Future[Seq[DeletedRepository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/decommissioned-repositories?repoType=service")
      .execute[Seq[DeletedRepository]]

  def cachedTeamToReposMap()(using hc: HeaderCarrier): Future[Map[String, Seq[String]]] =
    cache.getOrElseUpdate("teams-for-services", serviceConfiguration.teamsAndRepositoriesCacheExpiration):
      getAllRepositories(archived = Some(false))
        .map(_.map(x => (x.name, x.teamNames)).toMap)
