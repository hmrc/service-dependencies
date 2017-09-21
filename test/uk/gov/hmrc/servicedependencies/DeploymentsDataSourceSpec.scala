/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.OneAppPerSuite

class DeploymentsDataSourceSpec
  extends FreeSpec
  with MustMatchers
  with ScalaFutures
  with IntegrationPatience
  with BeforeAndAfterAll
  with OneAppPerSuite {

  val wireMock = new WireMockConfig(8080)

  override protected def beforeAll(): Unit = {
    wireMock.start()
    stubReleaseEnvironment("qa")
    stubReleaseEnvironment("staging")
    stubReleaseEnvironment("prod")
  }

  private def stubReleaseEnvironment(environment: String) = {
    wireMock.stub(get(urlEqualTo(s"/env/$environment"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(loadFileAsString(s"/releases/$environment.json"))))
  }

  override protected def afterAll(): Unit = {
    wireMock.stop()
  }

  private def loadFileAsString(filename: String): String = {
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString
  }

//  "Retrieving a list of running services" - {
//    "correctly parse json response" in {
//      val services = new DeploymentsDataSource(new ReleasesConfig {
//        override def releasesServiceUrl: String = wireMock.host()
//      })
//
//      val runningServices = services.listOfRunningServices().futureValue
//      runningServices.length mustBe 162
//    }
//  }
}
