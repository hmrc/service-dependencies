/*
 * Copyright 2017 HM Revenue & Customs
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
import uk.gov.hmrc.HttpClient
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig

import scala.concurrent.Future

//trait TeamsAndRepositoriesDataSource {
//  def getTeamsForRepository(repositoryName: String): Future[Seq[String]]
//  def getTeamsForServices(): Future[Map[String, Seq[String]]]
//  def getAllRepositories(): Future[Seq[String]]
//}

@Singleton
class TeamsAndRepositoriesDataSource @Inject() (serviceConfiguration: ServiceDependenciesConfig) {

  val teamsAndRepositoriesApiBase = serviceConfiguration.teamsAndRepositoriesServiceUrl

  def getTeamsForRepository(repositoryName: String): Future[Seq[String]] =
    HttpClient.getWithParsing[List[String]](s"$teamsAndRepositoriesApiBase/api/repositories/$repositoryName"){json =>
      (json \ "teamNames").as[List[String]]
    }

  def getTeamsForServices(): Future[Map[String, Seq[String]]] =
    HttpClient.getWithParsing[Map[String, Seq[String]]](s"$teamsAndRepositoriesApiBase/api/services?teamDetails=true") { json =>
      json.as[Map[String, Seq[String]]]
    }

  def getAllRepositories(): Future[Seq[String]] =
    HttpClient.getWithParsing[Seq[String]](s"$teamsAndRepositoriesApiBase/api/repositories") { json =>
    (json \\ "name").map(_.as[String])// this is the json: http://catalogue.tax.service.gov.uk/api/repositories
  }
}
