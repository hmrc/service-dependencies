/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies

import play.api.Logger
import play.api.libs.json.Json
import uk.gov.hmrc.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Service(name: String, qaVersion: Option[String], stagingVersion: Option[String], prodVersion: Option[String])

private case class Deployment(an: String, ver: String)

class DeploymentsDataSource(releasesConfig: ReleasesConfig) {

  def listOfRunningServices(): Future[List[Service]] = {
    implicit val deploymentReads = Json.reads[Deployment]

    val releasesHost = releasesConfig.releasesServiceUrl
    Logger.info(s"Fetching list of running services from $releasesHost")

    val results = for {
      qaReleases <- HttpClient.get[List[Deployment]](s"$releasesHost/env/qa")
      stagingReleases <- HttpClient.get[List[Deployment]](s"$releasesHost/env/staging")
      prodReleases <- HttpClient.get[List[Deployment]](s"$releasesHost/env/prod")
    } yield join(
      qaReleases.map(release => release.an -> release.ver).toMap,
      stagingReleases.map(release => release.an -> release.ver).toMap,
      prodReleases.map(release => release.an -> release.ver).toMap).toList

    results.onSuccess { case s => Logger.info(s"Found ${s.length} running services") }
    results
  }

  private def join(qaReleases: Map[String, String], stagingReleases: Map[String, String], prodReleases: Map[String, String]) = {
    val services: Set[String] = qaReleases.keySet ++ stagingReleases.keySet ++ prodReleases.keySet
    services.map {
      case service => Service(service, qaReleases.get(service), stagingReleases.get(service), prodReleases.get(service))
    }
  }
}
