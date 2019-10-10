/*
 * Copyright 2019 HM Revenue & Customs
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
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Logger
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.model.Version

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}


@Singleton
class ServiceDeploymentsConnector @Inject()(httpClient: HttpClient, servicesConfig: ServicesConfig) {
  import ServiceDeploymentsConnector._
  import ExecutionContext.Implicits.global

  private val serviceUrl: String = servicesConfig.baseUrl("service-deployments")

  implicit val sdir = ServiceDeploymentInformation.reads

  def getWhatIsRunningWhere(implicit hc: HeaderCarrier): Future[Seq[ServiceDeploymentInformation]] =
    httpClient.GET[Seq[ServiceDeploymentInformation]](s"$serviceUrl/api/whatsrunningwhere")
}

object ServiceDeploymentsConnector {
  sealed trait Environment
  object Environment {
    case object Production   extends Environment
    case object ExternalTest extends Environment
    case object Staging      extends Environment
    case object QA           extends Environment
    case object Integration  extends Environment
    case object Development  extends Environment

    val reads: Reads[Option[Environment]] =
      (__ \ "name").read[String].map(_ match {
        case "production"    => Some(Production)
        case "external test" => Some(ExternalTest)
        case "staging"       => Some(Staging)
        case "qa"            => Some(QA)
        case "integration"   => Some(Integration)
        case "development"   => Some(Development)
        case other           => Logger.debug(s"Unsupported environment '$other'"); None
      })
  }

  case class Deployment(
      optEnvironment: Option[Environment]
    , version       : Version
    )

  object Deployment {
    val reads: Reads[Deployment] = {
      implicit val dr = Environment.reads
      implicit val vr = Version.apiFormat
      ( (__ \ "environmentMapping").read[Option[Environment]]
      ~ (__ \ "version"           ).read[Version]
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
      ( (__ \ "serviceName").read[String]
      ~ (__ \ "deployments").read[Seq[Deployment]]
      )(ServiceDeploymentInformation.apply _)
    }
  }
}
