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

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.servicedependencies.config.ReleasesApiConfig
import uk.gov.hmrc.servicedependencies.model.Version

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class ReleasesApiConnector @Inject()(
  httpClientV2  : HttpClientV2,
  config        : ReleasesApiConfig
)(using
  ec: ExecutionContext
):
  import ReleasesApiConnector._
  import HttpReads.Implicits._

  private val serviceUrl: String = config.serviceUrl

  given Reads[ServiceDeploymentInformation] = ServiceDeploymentInformation.reads

  def getWhatIsRunningWhere()(using hc: HeaderCarrier): Future[Seq[ServiceDeploymentInformation]] =
    httpClientV2
      .get(url"$serviceUrl/releases-api/whats-running-where")
      .execute[Seq[ServiceDeploymentInformation]]

object ReleasesApiConnector extends Logging:
  enum Environment:
    case Production
    case ExternalTest
    case Staging
    case QA
    case Integration
    case Development

  object Environment:
    given Reads[Option[Environment]] =
      JsPath.read[String].map {
        case "production"   => Some(Production)
        case "externaltest" => Some(ExternalTest)
        case "staging"      => Some(Staging)
        case "qa"           => Some(QA)
        case "integration"  => Some(Integration)
        case "development"  => Some(Development)
        case other          => logger.debug(s"Unsupported environment '$other'"); None
      }

  case class Deployment(
    optEnvironment: Option[Environment]
  , version       : Version
  )

  object Deployment:
    val reads: Reads[Deployment] =
      import Environment.given
      import Version.given
      ( (__ \ "environment"  ).read[Option[Environment]]
      ~ (__ \ "versionNumber").read[Version]
      )(Deployment.apply)

  case class ServiceDeploymentInformation(
    serviceName: String
  , deployments: Seq[Deployment]
  )

  object ServiceDeploymentInformation:
    val reads: Reads[ServiceDeploymentInformation] =
      given Reads[Deployment] = Deployment.reads
      ( (__ \ "applicationName").read[String]
      ~ (__ \ "versions"       ).read[Seq[Deployment]]
      )(ServiceDeploymentInformation.apply)
