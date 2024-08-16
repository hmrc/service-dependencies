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

import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.json.{JsResult, Reads, Writes, __}
import uk.gov.hmrc.servicedependencies.model.Version
import play.api.libs.functional.syntax.*

@Singleton
class VulnerabilitiesConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig
):
  import uk.gov.hmrc.http.HttpReads.Implicits._

  private val url: String = servicesConfig.baseUrl("vulnerabilities")

  def vulnerabilitySummaries(
    serviceName: Option[String] = None
  )(using
    ec: ExecutionContext
  ): Future[Seq[DistinctVulnerability]] =
    given HeaderCarrier = HeaderCarrier()
    given Reads[DistinctVulnerability] = DistinctVulnerability.reads

    httpClientV2
      .get(url"$url/vulnerabilities/api/summaries?service=$serviceName&curationStatus=ACTION_REQUIRED")
      .execute[Seq[DistinctVulnerability]]
      .map(_.filterNot(_.id.contains("VulnDB"))) //TODO investigate vulnDB and remove filter

end VulnerabilitiesConnector

case class DistinctVulnerability(
  vulnerableComponentName   : String, // format is  gav://example:example
  vulnerableComponentVersion: String,
  id                        : String
){
  def strippedVulnerableComponentName = vulnerableComponentName.replaceAll("^.*://", "") //returns all after  gav://
  def dependencyType                  = vulnerableComponentName.replaceAll("://.*$", "") //returns suffix before  ://
}

object DistinctVulnerability {

  val reads: Reads[DistinctVulnerability] =
    ( (__ \ "distinctVulnerability" \ "vulnerableComponentName"   ).read[String]
    ~ (__ \ "distinctVulnerability" \ "vulnerableComponentVersion").read[String]
    ~ (__ \ "distinctVulnerability" \ "id"                        ).read[String]
    )(apply)
}
