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
import uk.gov.hmrc.servicedependencies.model.RepoType

import scala.concurrent.{ExecutionContext, Future}

object TeamsAndRepositoriesConnector:
  import play.api.libs.functional.syntax.*

  case class Repository(
    name          : String
  , teamNames     : Seq[String]
  , digitalService: Option[String]
  , repoType      : RepoType
  , isArchived    : Boolean
  )

  object Repository:
    val reads: Reads[Repository] =
      ( (__ \ "name"          ).read[String]
      ~ (__ \ "teamNames"     ).read[Seq[String]]
      ~ (__ \ "digitalService").readNullable[String]
      ~ (__ \ "repoType"      ).read[RepoType]
      ~ (__ \ "isArchived"    ).read[Boolean]
      )(apply)

  case class DecommissionedRepository(name: String)

  object DecommissionedRepository:
    val reads: Reads[DecommissionedRepository] =
      (__ \ "repoName")
        .read[String]
        .map:
          DecommissionedRepository.apply

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
  private given Reads[Repository]               = Repository.reads
  private given Reads[DecommissionedRepository] = DecommissionedRepository.reads

  def getRepository(repositoryName: String)(using hc: HeaderCarrier): Future[Option[Repository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/repositories/$repositoryName")
      .execute[Option[Repository]]

  def getAllRepositories(
    archived      : Option[Boolean]
  , teamName      : Option[String]   = None
  , digitalService: Option[String]   = None
  , repoType      : Option[RepoType] = None
  )(using hc: HeaderCarrier): Future[Seq[Repository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/repositories?archived=$archived&team=$teamName&digitalServiceName=$digitalService&repoType=${repoType.map(_.asString)}")
      .execute[Seq[Repository]]

  def getDecommissionedRepositories(
    repoType: Option[RepoType] = None
  )(using hc: HeaderCarrier): Future[Seq[DecommissionedRepository]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesApiBase/api/v2/decommissioned-repositories?repoType=${repoType.map(_.asString)}")
      .execute[Seq[DecommissionedRepository]]

  def cachedRepoMap()(using hc: HeaderCarrier): Future[Map[String, (List[String], Option[String])]] =
    cache.getOrElseUpdate("teams-for-services", serviceConfiguration.teamsAndRepositoriesCacheExpiration):
      getAllRepositories(archived = Some(false))
        .map(_.map(x => (x.name, (x.teamNames.sorted.toList, x.digitalService))).toMap)
