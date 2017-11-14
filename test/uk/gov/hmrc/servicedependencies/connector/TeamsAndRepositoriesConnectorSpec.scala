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

package uk.gov.hmrc.servicedependencies.connector

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FreeSpec, MustMatchers}
import org.scalatestplus.play.OneAppPerSuite
import play.api.{Configuration, Environment}
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.http.ws.WSHttp
import uk.gov.hmrc.servicedependencies.WireMockConfig
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig

class TeamsAndRepositoriesConnectorSpec
  extends FreeSpec
  with MustMatchers
  with ScalaFutures
  with IntegrationPatience
  with BeforeAndAfterAll
  with OneAppPerSuite
  with MockitoSugar {

  val wireMock = new WireMockConfig(8080)

  implicit val hc = HeaderCarrier()

  override protected def beforeAll(): Unit = {
    wireMock.start()

    stubRepositories("test-repo")
    stubRepositoriesWith404("non-existing-test-repo")
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

  private def stubRepositoriesWith404(repositoryName: String) = {
    wireMock.stub(get(urlEqualTo(s"/api/repositories/$repositoryName"))
      .willReturn(
        aResponse()
          .withStatus(404)))
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

  def stubbedConfig = new ServiceDependenciesConfig(Configuration(), Environment.simple()) {
    override lazy val teamsAndRepositoriesServiceUrl = wireMock.host()
  }

  val httpClient = new HttpClient with WSHttp {
    override val hooks: Seq[HttpHook] = NoneRequired
  }

  "Retrieving a repository" - {
    "correctly parse json response" in {
      val services = new TeamsAndRepositoriesConnector(httpClient, stubbedConfig)

      val repository = services.getRepository("test-repo").futureValue
      repository.get.teamNames mustBe Seq("PlatOps", "Webops")
    }

    "handle 404 - repository not found" in {
      val services = new TeamsAndRepositoriesConnector(httpClient, stubbedConfig)

      val repository = services.getRepository("non-existing-test-repo").futureValue
      repository mustBe None
    }
  }

  "Retrieving a list of teams for all services" - {
    "correctly parse json response" in {
      val services = new TeamsAndRepositoriesConnector(httpClient, stubbedConfig)

      val teams = services.getTeamsForServices().futureValue
      teams mustBe Map(
        "test-repo" -> Seq("PlatOps", "WebOps"),
        "another-repo" -> Seq("PlatOps"))
    }
  }

  "Retrieving a list of all repositories" - {
    "correctly parse json response" in {
      val services = new TeamsAndRepositoriesConnector(httpClient, stubbedConfig)

      val repositories = services.getAllRepositories().futureValue
      repositories mustBe Seq("test-repo", "another-repo")
    }
  }

}
