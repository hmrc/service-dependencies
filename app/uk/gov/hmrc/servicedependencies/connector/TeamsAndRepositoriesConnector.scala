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

package uk.gov.hmrc.servicedependencies.connector

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.model.Repository

import scala.concurrent.Future
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

@Singleton
class TeamsAndRepositoriesConnector @Inject()(httpClient: HttpClient, serviceConfiguration: ServiceDependenciesConfig) {

  val teamsAndRepositoriesApiBase = serviceConfiguration.teamsAndRepositoriesServiceUrl

  implicit val formats = Repository.f

  def getRepository(repositoryName: String)(implicit hc: HeaderCarrier): Future[Option[Repository]] =
    httpClient.GET[Option[Repository]](s"$teamsAndRepositoriesApiBase/api/repositories/$repositoryName")

  def getTeamsForServices()(implicit hc: HeaderCarrier): Future[Map[String, Seq[String]]] =
    httpClient.GET[Map[String, Seq[String]]](s"$teamsAndRepositoriesApiBase/api/services?teamDetails=true")

  def getAllRepositories()(implicit hc: HeaderCarrier): Future[Seq[String]] =
    httpClient.GET(s"$teamsAndRepositoriesApiBase/api/repositories") map { response =>
      (response.json \\ "name").map(_.as[String])
    }
}
