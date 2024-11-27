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

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}

import scala.concurrent.ExecutionContext.Implicits.global

class VulnerabilitiesConnectorSpec
  extends AnyWordSpec
     with Matchers
     with OptionValues
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support:

  given HeaderCarrier = HeaderCarrier()

  val servicesConfig =
    ServicesConfig(Configuration(
      "microservice.services.vulnerabilities.host" -> wireMockHost,
      "microservice.services.vulnerabilities.port" -> wireMockPort
    ))

  wireMockConfig().withRootDirectory("test/resources")

  private val connector = VulnerabilitiesConnector(httpClientV2, servicesConfig)

  "VulnerabilitiesConnector.getRepository" should:
    "correctly parse json response" in:
      val service = "Service_A"
      val version = "1.0.0"
      val flag    = "latest"
      stubFor(
        get(urlEqualTo(s"/vulnerabilities/api/summaries?service=%22$service%22&version=$version&flag=$flag&curationStatus=ACTION_REQUIRED"))
          .willReturn(aResponse().withBodyFile("vulnerabilities/summaries.json"))
      )

      connector.vulnerabilitySummaries(Some(service), Some(version), Some(flag)).futureValue shouldBe Seq(
        DistinctVulnerability(
          vulnerableComponentName    = "gav://g:a"
        , vulnerableComponentVersion = "1.0"
        , id                         = "CVE-1"
        , occurrences                = List(
            VulnerableComponent(
              "gav://g:a",
              "1.0",
              "Service_A-1.0.0/lib/g.a-1.0.jar"
            )
          )
        ),
        DistinctVulnerability(
          vulnerableComponentName    = "gav://g:a"
        , vulnerableComponentVersion = "1.0"
        , id                         = "CVE-2"
        , occurrences                = List(
            VulnerableComponent(
              "gav://g:a",
              "1.0",
              "Service_A-1.0.0/lib/g.a-1.0.jar"
            )
          )
        )
      )

  "DistinctVulnerability.matchesGav" should:
    "match gav" in:
      val vulnerability =
        DistinctVulnerability(
          vulnerableComponentName    = "gav://g:a"
        , vulnerableComponentVersion = "1.0"
        , id                         = "CVE-1"
        , occurrences                = List(
            VulnerableComponent(
              "gav://g:a",
              "1.0",
              "Service_A-1.0.0/lib/g.a-1.0.jar"
            )
          )
        )

      vulnerability.matchesGav(group = "g", artefact = "a", version = "1.0") shouldBe true

    "match component in path" in:
      val vulnerability =
        DistinctVulnerability(
          vulnerableComponentName    = "gav://g:a"
        , vulnerableComponentVersion = "1.0"
        , id                         = "CVE-1"
        , occurrences                = List(
            VulnerableComponent(
              "gav://g:a",
              "1.0",
              "Service_A-1.0.0/lib/g2.a2-1.0.jar/META-INF/maven/g/a/pom.xml"
            )
          )
        )

      vulnerability.matchesGav(group = "g2", artefact = "a2", version = "1.0") shouldBe true
