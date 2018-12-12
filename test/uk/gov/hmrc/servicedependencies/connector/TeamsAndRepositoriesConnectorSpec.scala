/*
 * Copyright 2018 HM Revenue & Customs
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

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FreeSpec, MustMatchers}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Application
import uk.gov.hmrc.http._
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.servicedependencies.WireMockConfig
import com.github.tomakehurst.wiremock.client.WireMock._
import org.joda.time.DateTime
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo

class TeamsAndRepositoriesConnectorSpec
    extends FreeSpec
    with MustMatchers
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
    stubTeamsWithRepositories()
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

  private val services = app.injector.instanceOf[TeamsAndRepositoriesConnector]


  "Retrieving a repository" - {
    "correctly parse json response" in {

      val repository = services.getRepository("test-repo").futureValue
      repository.get.teamNames mustBe Seq("PlatOps", "Webops")
    }

    "handle 404 - repository not found" in {

      val repository = services.getRepository("non-existing-test-repo").futureValue
      repository mustBe None
    }
  }

  "Retrieving a list of teams for all services" - {
    "correctly parse json response" in {

      val teams = services.getTeamsForServices().futureValue
      teams mustBe Map("test-repo" -> Seq("PlatOps", "WebOps"), "another-repo" -> Seq("PlatOps"))
    }
  }

  "Retrieving a list of all repositories" - {
    "correctly parse json response" in {
      val repositories = services.getAllRepositories().futureValue
      repositories mustBe List(
        RepositoryInfo("test-repo", new DateTime("2015-09-15T17:27:38.000+01:00"), new DateTime("2017-05-19T12:00:51.000+01:00"),"Prototype",None),
        RepositoryInfo("another-repo",new DateTime("2016-05-12T11:18:53.000+01:00"), new DateTime("2016-05-12T16:43:32.000+01:00"),"Prototype",None))
    }
  }

  "Retrieving a list of all teams with repositories" - {
    "correctly parse json response" in {

      val teamsWithRepositories = services.getTeamsWithRepositories().futureValue
      teamsWithRepositories mustBe Seq(
        Team("team A", Some(Map("Service" -> Seq("service A", "service B"), "Library" -> Seq("library A"))))
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
      get(urlEqualTo(s"/api/services?teamDetails=true"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(loadFileAsString(s"/teams-and-repositories/service-teams.json"))))

  private def stubTeamsWithRepositories() =
    wireMock.stub(
      get(urlEqualTo(s"/api/teams_with_repositories"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |[
                         |{ "name": "team A", "repos": { "Service": [ "service A", "service B" ], "Library": [ "library A" ] } }
                         |]
               """.stripMargin)
        )
    )
}
