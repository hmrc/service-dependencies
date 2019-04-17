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

import java.time.LocalDate

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FreeSpec, MustMatchers}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.WireMockConfig
import uk.gov.hmrc.servicedependencies.connector.model.{BobbyRule, BobbyVersionRange, DeprecatedDependencies}

class ServiceConfigsConnectorSpec
    extends FreeSpec
    with MustMatchers
    with ScalaFutures
    with IntegrationPatience
    with BeforeAndAfterAll
    with GuiceOneAppPerSuite
    with MockitoSugar {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val wireMock = new WireMockConfig()

  override protected def beforeAll(): Unit = {
    wireMock.start()

    stubGetBobbyRules()
  }

  override protected def afterAll(): Unit =
    wireMock.stop()

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.service-configs.port" -> wireMock.stubPort,
        "play.http.requestHandler"                   -> "play.api.http.DefaultHttpRequestHandler",
        "metrics.jvm"                                -> false
      )
      .build()

  private val services = app.injector.instanceOf[ServiceConfigsConnector]

  "Retrieving bobby rules" - {
    "correctly parse json response" in {

      val deprecatedDependencies = services.getBobbyRules().futureValue

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

      deprecatedDependencies must contain theSameElementsAs Map(
        sbtAutoBuild.name -> List(sbtAutoBuild),
        playFrontend.name -> List(playFrontend))
    }
  }

  private def loadFileAsString(filename: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString

  private def stubGetBobbyRules(): Unit =
    wireMock.stub(
      get(urlEqualTo(s"/bobby/rules"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(loadFileAsString(s"/service-configs/bobby-rules.json"))))

}
