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

import java.time.LocalDate

import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.servicedependencies.model.{BobbyRule, BobbyVersionRange}

class ServiceConfigsConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterAll
     with GuiceOneAppPerSuite
     with MockitoSugar
     with WireMockSupport {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  override lazy val resetWireMockMappings = false

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    stubGetBobbyRules()
  }

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.service-configs.host" -> wireMockHost,
        "microservice.services.service-configs.port" -> wireMockPort
      )
      .build()

  private val connector = app.injector.instanceOf[ServiceConfigsConnector]

  "Retrieving bobby rules" should {
    "correctly parse json response" in {
      val deprecatedDependencies = connector.getBobbyRules().futureValue

      val playFrontend = BobbyRule(
        organisation = "uk.gov.hmrc",
        name         = "play-frontend",
        range        = BobbyVersionRange("(,99.99.99)"),
        reason       = "Post Play Frontend upgrade",
        from         = LocalDate.of(2015, 11, 2)
      )

      val sbtAutoBuild = BobbyRule(
        organisation = "uk.gov.hmrc",
        name         = "sbt-auto-build",
        range        = BobbyVersionRange("(,1.4.0)"),
        reason       = "Play 2.5 upgrade",
        from         = LocalDate.of(2017, 5, 1)
      )

      deprecatedDependencies.asMap should contain theSameElementsAs Map(
        (sbtAutoBuild.organisation, sbtAutoBuild.name) -> List(sbtAutoBuild),
        (playFrontend.organisation, playFrontend.name) -> List(playFrontend)
      )
    }
  }

  private def stubGetBobbyRules(): Unit =
    stubFor(
      get(urlEqualTo(s"/service-configs/bobby/rules"))
        .willReturn(aResponse().withBodyFile(s"service-configs/bobby-rules.json"))
    )
}
