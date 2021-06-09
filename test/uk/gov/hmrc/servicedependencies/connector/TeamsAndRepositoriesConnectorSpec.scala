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
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo

class TeamsAndRepositoriesConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterAll
     with GuiceOneAppPerSuite
     with MockitoSugar
     with WireMockSupport {

  implicit val hc = HeaderCarrier()

  override lazy val resetWireMockMappings = false

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    stubRepositories("test-repo")
    stubRepositoriesWith404("non-existing-test-repo")
    stubAllRepositories()
    stubServices()
  }

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.teams-and-repositories.host" -> wireMockHost,
        "microservice.services.teams-and-repositories.port" -> wireMockPort,
        "play.http.requestHandler" -> "play.api.http.DefaultHttpRequestHandler",
        "metrics.jvm" -> false
      ).build()

  private val connector = app.injector.instanceOf[TeamsAndRepositoriesConnector]


  "Retrieving a repository" should {
    "correctly parse json response" in {
      val repository = connector.getRepository("test-repo").futureValue
      repository.get.teamNames shouldBe Seq("PlatOps", "Webops")
    }

    "handle 404 - repository not found" in {
      val repository = connector.getRepository("non-existing-test-repo").futureValue
      repository shouldBe None
    }
  }

  "Retrieving a list of teams for all services" should {
    "correctly parse json response" in {
      val teams = connector.getTeamsForServices.futureValue
      teams shouldBe TeamsForServices(Map("test-repo" -> Seq("PlatOps", "WebOps"), "another-repo" -> Seq("PlatOps")))
    }
  }

  "Retrieving a list of all repositories" should {
    "correctly parse json response" in {
      val repositories = connector.getAllRepositories(archived = None).futureValue
      repositories shouldBe List(
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

  private def stubRepositories(repositoryName: String) =
    stubFor(
      get(urlEqualTo(s"/api/repositories/$repositoryName"))
        .willReturn(aResponse().withBodyFile("/teams-and-repositories/repository.json"))
    )

  private def stubRepositoriesWith404(repositoryName: String) =
    stubFor(
      get(urlEqualTo(s"/api/repositories/$repositoryName"))
        .willReturn(
          aResponse()
            .withStatus(404)
        )
    )

  private def stubAllRepositories() =
    stubFor(
      get(urlEqualTo("/api/repositories"))
        .willReturn(aResponse().withBodyFile("/teams-and-repositories/repositories.json"))
    )

  private def stubServices() =
    stubFor(
      get(urlEqualTo("/api/repository_teams"))
        .willReturn(aResponse().withBodyFile("/teams-and-repositories/service-teams.json"))
    )
}
