/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpReads}
import uk.gov.hmrc.servicedependencies.config.ReleasesApiConfig
import uk.gov.hmrc.servicedependencies.model.Version

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class ReleasesApiConnector @Inject()(
  httpClient    : HttpClient,
  config        : ReleasesApiConfig
)(implicit ec: ExecutionContext
) {
  import ReleasesApiConnector._
  import HttpReads.Implicits._

  private val serviceUrl: String = config.serviceUrl

  implicit val sdir = ServiceDeploymentInformation.reads

  def getWhatIsRunningWhere(implicit hc: HeaderCarrier): Future[Seq[ServiceDeploymentInformation]] =
    httpClient.GET[Seq[ServiceDeploymentInformation]](s"$serviceUrl/releases-api/whats-running-where")
}

object ReleasesApiConnector extends Logging {
  sealed trait Environment
  object Environment {
    case object Production   extends Environment
    case object ExternalTest extends Environment
    case object Staging      extends Environment
    case object QA           extends Environment
    case object Integration  extends Environment
    case object Development  extends Environment

    val reads: Reads[Option[Environment]] =
      JsPath.read[String].map {
        case "production"   => Some(Production)
        case "externaltest" => Some(ExternalTest)
        case "staging"      => Some(Staging)
        case "qa"           => Some(QA)
        case "integration"  => Some(Integration)
        case "development"  => Some(Development)
        case other          => logger.debug(s"Unsupported environment '$other'"); None
      }
  }

  case class Deployment(
      optEnvironment: Option[Environment]
    , version       : Version
    )

  object Deployment {
    val reads: Reads[Deployment] = {
      implicit val dr = Environment.reads
      implicit val vr = Version.apiFormat
      ( (__ \ "environment"  ).read[Option[Environment]]
      ~ (__ \ "versionNumber").read[Version]
      )(Deployment.apply _)
    }
  }

  case class ServiceDeploymentInformation(
      serviceName: String
    , deployments: Seq[Deployment]
    )

  object ServiceDeploymentInformation {
    val reads: Reads[ServiceDeploymentInformation] = {
      implicit val dr = Deployment.reads
      ( (__ \ "applicationName").read[String]
      ~ (__ \ "versions"       ).read[Seq[Deployment]]
      )(ServiceDeploymentInformation.apply _)
    }
  }
}
