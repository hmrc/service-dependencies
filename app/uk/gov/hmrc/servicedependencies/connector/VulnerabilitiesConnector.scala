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
import play.api.libs.json.{JsValue, JsResult, Reads, __}
import play.api.libs.functional.syntax.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class VulnerabilitiesConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig
)(using
  ec: ExecutionContext
):
  import uk.gov.hmrc.http.HttpReads.Implicits._

  private val url: String = servicesConfig.baseUrl("vulnerabilities")

  def vulnerabilitySummaries(
    serviceName: Option[String] = None
  , version    : Option[String] = None
  , flag       : Option[String] = None
  )(using
    HeaderCarrier
  ): Future[Seq[DistinctVulnerability]] =
    given Reads[DistinctVulnerability] = DistinctVulnerability.reads

    httpClientV2
      .get(url"$url/vulnerabilities/api/summaries?service=${serviceName.map(sn => s"\"$sn\"")}&version=$version&flag=$flag&curationStatus=ACTION_REQUIRED")
      .execute[Seq[DistinctVulnerability]]
      .map(_.filterNot(_.id.contains("VulnDB"))) //TODO investigate vulnDB and remove filter

end VulnerabilitiesConnector

case class DistinctVulnerability(
  vulnerableComponentName   : String, // format is  gav://example:example
  vulnerableComponentVersion: String,
  id                        : String,
  occurrences               : Seq[VulnerableComponent]
):
  def matchesGav(group: String, artefact: String, version: String): Boolean =
    occurrences.exists(_.matchesGav(group, artefact, version))

object DistinctVulnerability:

  val reads: Reads[DistinctVulnerability] =
    given Reads[VulnerableComponent] = VulnerableComponent.reads
    ( (__ \ "distinctVulnerability" \ "vulnerableComponentName"   ).read[String]
    ~ (__ \ "distinctVulnerability" \ "vulnerableComponentVersion").read[String]
    ~ (__ \ "distinctVulnerability" \ "id"                        ).read[String]
    ~ (__ \ "occurrences"                                         ).read[Seq[VulnerableComponent]]
    )(apply)

case class VulnerableComponent(
  name    : String,
  version : String,
  path    : String
):
  private val jarRegex = raw".*\/([^\/]+)\.jar.*".r

  def matchesGav(group: String, artefact: String, version: String): Boolean =
    if this.name == s"gav://$group:$artefact" && this.version == version
    then
      true
    else
      this.path match
        case jarRegex(jar) => jar == s"$group.$artefact-$version"
        case _             => false

object VulnerableComponent:
  val reads: Reads[VulnerableComponent] =
    ( (__ \ "vulnerableComponentName"   ).read[String]
    ~ (__ \ "vulnerableComponentVersion").read[String]
    ~ (__ \ "componentPathInSlug"       ).read[String]
    )(apply)
