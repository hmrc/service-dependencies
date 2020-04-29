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

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.libs.json.{JsSuccess, Json}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.WireMockConfig
import uk.gov.hmrc.servicedependencies.config.ReleasesApiConfig
import uk.gov.hmrc.servicedependencies.connector.ReleasesApiConnector.Environment

import scala.concurrent.ExecutionContext.Implicits.global

class ReleasesApiConnectorSpec
  extends AnyWordSpec
  with Matchers
  with ScalaFutures
  with BeforeAndAfterAll
  with GuiceOneAppPerSuite
  with IntegrationPatience {

  implicit val hc = HeaderCarrier()

  val wireMock = new WireMockConfig()
  println(wireMock.stubPort)

  private val httpClient = app.injector.instanceOf[HttpClient]

  val config = new ReleasesApiConfig(null) {
    override lazy val serviceUrl: String = s"http://localhost:${wireMock.stubPort}"
  }
  val connector = new ReleasesApiConnector(httpClient, config)

  override protected def beforeAll(): Unit = {
    wireMock.start()
    stubWhatsRunningWhere()
  }

  "Retrieving whatsrunningwhere" should {
    "download whatsrunning where data " in {
      val res = connector.getWhatIsRunningWhere.futureValue
      res.length shouldBe 5
    }
  }

  "Environment" should {
    "correctly parse releases-api environment names" in {
      implicit val reads = ReleasesApiConnector.Environment.reads
      Json.fromJson[Option[Environment]](Json.parse("\"production\"")) shouldBe JsSuccess(Some(Environment.Production))
      Json.fromJson[Option[Environment]](Json.parse("\"development\"")) shouldBe JsSuccess(Some(Environment.Development))
      Json.fromJson[Option[Environment]](Json.parse("\"integration\"")) shouldBe JsSuccess(Some(Environment.Integration))
      Json.fromJson[Option[Environment]](Json.parse("\"qa\"")) shouldBe JsSuccess(Some(Environment.QA))
      Json.fromJson[Option[Environment]](Json.parse("\"staging\"")) shouldBe JsSuccess(Some(Environment.Staging))
      Json.fromJson[Option[Environment]](Json.parse("\"externaltest\"")) shouldBe JsSuccess(Some(Environment.ExternalTest))
      Json.fromJson[Option[Environment]](Json.parse("\"foo\"")) shouldBe JsSuccess(None)
    }
  }

  def stubWhatsRunningWhere() =
    wireMock.stub(
      get(urlEqualTo("/releases-api/whats-running-where"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(loadFileAsString(s"/releases-api/whatsrunningwhere.json"))
        )
    )

  private def loadFileAsString(filename: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString

}
