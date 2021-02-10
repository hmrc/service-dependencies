/*
 * Copyright 2021 HM Revenue & Customs
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

import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig

import scala.concurrent.{ExecutionContext, Future}

class GithubRawConnectorSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  import ExecutionContext.Implicits.global

  "GithubRawConnector" should {

    "parse decommissioned services" in {
      val boot = Boot.init

      val body =
        """|- database_name: false
           |  service_name: cds-stub
           |  ticket_id: SUP-11290
           |- database_name: journey-backend-transport
           |  service_name: journey-backend-transport
           |  ticket_id: SUP-11286
           """.stripMargin

      when(boot.mockHttpClient.GET[Either[UpstreamErrorResponse, HttpResponse]](any(), any())(any(), any(), any()))
        .thenReturn(Future.successful(Right(HttpResponse(200, body))))

      boot.githubRawConnector.decomissionedServices(HeaderCarrier()).futureValue shouldBe
        List(
          "cds-stub"
        , "journey-backend-transport"
        )
    }
  }

  case class Boot(
    mockHttpClient    : HttpClient
  , githubRawConnector: GithubRawConnector
  )

  object Boot {
    def init(): Boot = {
      val mockHttpClient     = mock[HttpClient]
      val mockServicesConfig = mock[ServicesConfig]

      val serviceDependenciesConfig = new ServiceDependenciesConfig(Configuration(ConfigFactory.load()), mockServicesConfig)

      val githubRawConnector = new GithubRawConnector(
          mockHttpClient
        , serviceDependenciesConfig
        )

      Boot(
        mockHttpClient
      , githubRawConnector
      )
    }
  }
}
