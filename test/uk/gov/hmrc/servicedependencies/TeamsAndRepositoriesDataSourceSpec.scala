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
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, MustMatchers}
import org.scalatestplus.play.OneAppPerSuite
import uk.gov.hmrc.servicedependencies.service.TeamsAndRepositoriesClient

class TeamsAndRepositoriesDataSourceSpec
  extends FreeSpec
  with MustMatchers
  with ScalaFutures
  with IntegrationPatience
  with BeforeAndAfterAll
  with OneAppPerSuite {

  val wireMock = new WireMockConfig(8080)

  override protected def beforeAll(): Unit = {
    wireMock.start()

    stubRepositories("test-repo")
    stubAllRepositories()
    stubServices()
  }

  private def stubRepositories(repositoryName: String) = {
    wireMock.stub(get(urlEqualTo(s"/api/repositories/$repositoryName"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(loadFileAsString(s"/teams-and-repositories/repository.json"))))
  }

  private def stubAllRepositories() = {

    wireMock.stub(get(urlEqualTo(s"/api/repositories"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(loadFileAsString(s"/teams-and-repositories/repositories.json"))))
  }

  private def stubServices() = {
    wireMock.stub(get(urlEqualTo(s"/api/services?teamDetails=true"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(loadFileAsString(s"/teams-and-repositories/service-teams.json"))))
  }

  override protected def afterAll(): Unit = {
    wireMock.stop()
  }

  private def loadFileAsString(filename: String): String = {
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString
  }

  "Retrieving a list of teams for a repository" - {
    "correctly parse json response" in {
      val services = new TeamsAndRepositoriesClient(wireMock.host())

      val teams = services.getTeamsForRepository("test-repo").futureValue
      teams mustBe Seq("PlatOps", "Webops")
    }
  }

  "Retrieving a list of teams for all services" - {
    "correctly parse json response" in {
      val services = new TeamsAndRepositoriesClient(wireMock.host())

      val teams = services.getTeamsForServices().futureValue
      teams mustBe Map(
        "test-repo" -> Seq("PlatOps", "WebOps"),
        "another-repo" -> Seq("PlatOps"))
    }
  }

  "Retrieving a list of all repositories" - {
    "correctly parse json response" in {
      val services = new TeamsAndRepositoriesClient(wireMock.host())

      val repositories = services.getAllRepositories().futureValue
      repositories mustBe Seq("test-repo", "another-repo")
    }
  }

}
