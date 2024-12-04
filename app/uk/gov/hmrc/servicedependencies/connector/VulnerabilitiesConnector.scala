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
import play.api.libs.json.{JsResult, Reads, __}
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
  vulnerableComponentName   : String, // format is  gav://group:artefact
  vulnerableComponentVersion: String,
  id                        : String,
  occurrences               : Seq[VulnerableComponent]
):
  // TODO also consider scalaVersion: Option[String] - but this is currently not returned when parsing graphs
  // for now just ignore if present in vulnerability name or path
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

  def matchesGav(group: String, artefact: String, version: String): Boolean =
    name match
      case VulnerableComponent.componentNameRegex(t, g, a)
        if t == "gav" && g == group && a == artefact && this.version == version
             => true
      case _ =>
        path match
          case VulnerableComponent.jarRegex(jar)
            // the jar may contain a scala version in the middle (between artefact and version) which we can ignore
            if jar.startsWith(s"$group.$artefact") && jar.endsWith(s"-$version")
                 => VulnerableComponent.optScalaVersionRegex.matches(jar.stripPrefix(s"$group.$artefact").stripSuffix(s"-$version"))
          case VulnerableComponent.jarRegex(jar)
            // the jar may contain a scala version in the middle (between artefact and version) which we can ignore
            // java wars have jars without the group in the name
            if jar.startsWith(s"$artefact") && jar.endsWith(s"-$version")
                 => VulnerableComponent.optScalaVersionRegex.matches(jar.stripPrefix(artefact).stripSuffix(s"-$version"))
          case _ => false

object VulnerableComponent:
  private val optScalaVersionRegex = raw"(?:_\d+(?:\.\d+)?)?".r
  private val componentNameRegex   = raw"(.*):\/\/(.*):(.*?)(?:_\d+(?:\.\d+)?)?".r // format is  gav://group:artefact with optional scala version
  private val jarRegex             = raw".*\/([^\/]+)\.jar.*".r

  val reads: Reads[VulnerableComponent] =
    ( (__ \ "vulnerableComponentName"   ).read[String]
    ~ (__ \ "vulnerableComponentVersion").read[String]
    ~ (__ \ "componentPathInSlug"       ).read[String]
    )(apply)
