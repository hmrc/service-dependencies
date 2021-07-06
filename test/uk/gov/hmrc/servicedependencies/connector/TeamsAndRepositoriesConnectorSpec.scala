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

import java.time.Instant

import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http._
import uk.gov.hmrc.servicedependencies.WireMockConfig
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class TeamsAndRepositoriesConnectorSpec
    extends AnyFreeSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience
    with BeforeAndAfterAll
    with GuiceOneAppPerSuite
    with MockitoSugar {

  implicit val hc = HeaderCarrier()

  val wireMock = new WireMockConfig()


  override protected def beforeAll(): Unit = {
    wireMock.start()

    stubRepositories("test-repo")
    stubRepositoriesWith404("non-existing-test-repo")
    stubAllRepositories()
    stubServices()
  }

  override protected def afterAll(): Unit =
    wireMock.stop()

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.teams-and-repositories.port" -> wireMock.stubPort,
        "play.http.requestHandler" -> "play.api.http.DefaultHttpRequestHandler",
        "metrics.jvm" -> false
      ).build()

  private val connector = app.injector.instanceOf[TeamsAndRepositoriesConnector]


  "Retrieving a repository" - {
    "correctly parse json response" in {
      val repository = connector.getRepository("test-repo").futureValue
      repository.get.teamNames mustBe Seq("PlatOps", "Webops")
    }

    "handle 404 - repository not found" in {
      val repository = connector.getRepository("non-existing-test-repo").futureValue
      repository mustBe None
    }
  }

  "Retrieving a list of teams for all services" - {
    "correctly parse json response" in {
      val teams = connector.getTeamsForServices.futureValue
      teams mustBe TeamsForServices(Map("test-repo" -> Seq("PlatOps", "WebOps"), "another-repo" -> Seq("PlatOps")))
    }
  }

  "Retrieving a list of all repositories" - {
    "correctly parse json response" in {
      val repositories = connector.getAllRepositories(archived = None).futureValue
      repositories mustBe List(
        RepositoryInfo(
            name          = "test-repo"
          , createdAt     = Instant.parse("2015-09-15T16:27:38.000Z")
          , lastUpdatedAt = Instant.parse("2017-05-19T11:00:51.000Z")
          , repoType      = "Prototype"
          , language      = None
          )
        , RepositoryInfo(
            name          = "another-repo"
          , createdAt     = Instant.parse("2016-05-12T10:18:53.000Z")
          , lastUpdatedAt = Instant.parse("2016-05-12T15:43:32.000Z")
          , repoType      = "Prototype"
          , language      = None
          )
        )
    }
  }

  private def loadFileAsString(filename: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString

  private def stubRepositories(repositoryName: String) =
    wireMock.stub(
      get(urlEqualTo(s"/api/repositories/$repositoryName"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(loadFileAsString(s"/teams-and-repositories/repository.json"))))

  private def stubRepositoriesWith404(repositoryName: String) =
    wireMock.stub(
      get(urlEqualTo(s"/api/repositories/$repositoryName"))
        .willReturn(aResponse()
          .withStatus(404)))

  private def stubAllRepositories() =
    wireMock.stub(
      get(urlEqualTo(s"/api/repositories"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(loadFileAsString(s"/teams-and-repositories/repositories.json"))))

  private def stubServices() =
    wireMock.stub(
      get(urlEqualTo(s"/api/repository_teams"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(loadFileAsString(s"/teams-and-repositories/service-teams.json"))))
}
