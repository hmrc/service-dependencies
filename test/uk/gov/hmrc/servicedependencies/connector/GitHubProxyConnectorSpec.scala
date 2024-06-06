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

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig

import scala.concurrent.ExecutionContext

class GitHubProxyConnectorSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support {

  import ExecutionContext.Implicits.global
  given HeaderCarrier = HeaderCarrier()

  "GitHubProxyConnector" should {
    "parse decommissioned services" in {
      val boot = Boot.init()

      val body =
        """|- database_name: false
           |  service_name: cds-stub
           |  ticket_id: SUP-11290
           |- database_name: journey-backend-transport
           |  service_name: journey-backend-transport
           |  ticket_id: SUP-11286
           """.stripMargin

      stubFor(
        get(urlEqualTo("/platops-github-proxy/github-raw/decommissioning/main/decommissioned-microservices.yaml"))
          .willReturn(aResponse().withBody(body))
      )

      boot.gitHubProxyConnector.decommissionedServices().futureValue shouldBe
        List(
          "cds-stub"
        , "journey-backend-transport"
        )
    }
  }

  case class Boot(
    gitHubProxyConnector: GitHubProxyConnector
  )

  object Boot {
    def init(): Boot = {
      val serviceDependenciesConfig = ServiceDependenciesConfig(
        Configuration(),
        ServicesConfig(Configuration(
          "microservice.services.platops-github-proxy.port" -> wireMockPort,
          "microservice.services.platops-github-proxy.host" -> wireMockHost
        ))
      )

      val gitHubProxyConnector = GitHubProxyConnector(
          httpClientV2,
          serviceDependenciesConfig
        )

      Boot(
        gitHubProxyConnector
      )
    }
  }
}
