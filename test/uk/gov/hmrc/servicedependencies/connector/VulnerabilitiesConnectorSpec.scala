/*
 * Copyright 2024 HM Revenue & Customs
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

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.ExecutionContext.Implicits.global

class VulnerabilitiesConnectorSpec
  extends AnyWordSpec
    with Matchers
    with OptionValues
    with ScalaFutures
    with IntegrationPatience
    with GuiceOneAppPerSuite
    with WireMockSupport {

  given HeaderCarrier = HeaderCarrier()

  override lazy val resetWireMockMappings = false

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure(
        "microservice.services.vulnerabilities.host" -> wireMockHost,
        "microservice.services.vulnerabilities.port" -> wireMockPort
      ).build()

  wireMockConfig().withRootDirectory("test/resources")

  private val connector = app.injector.instanceOf[VulnerabilitiesConnector]

  "VulnerabilitiesConnector.getRepository" should {
    "correctly parse json response" in {
      val service = Some("Service_A")
      val version = Some("1.0.0")
      val flag = Some("latest")
      stubFor(
        get(urlEqualTo(s"/vulnerabilities/api/summaries?service=${service.getOrElse("")}&version=${version.getOrElse("")}&flag=${flag.getOrElse("")}&curationStatus=ACTION_REQUIRED"))
          .willReturn(aResponse().withBodyFile("vulnerabilities/summaries.json"))
      )
      val repository = connector.vulnerabilitySummaries(service, version, flag).futureValue shouldBe Seq(
        DistinctVulnerability(
          vulnerableComponentName = "A"
        , vulnerableComponentVersion = "1.0"
        , id = "CVE-1"
        ),
        DistinctVulnerability(
          vulnerableComponentName = "A"
        , vulnerableComponentVersion = "1.0"
        , id = "CVE-2"
        )
      )
    }
  }
}
