/*
 * Copyright 2023 HM Revenue & Customs
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
import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.servicedependencies.model.RepoType

class TeamsAndRepositoriesConnectorSpec
  extends AnyWordSpec
     with Matchers
     with OptionValues
     with ScalaFutures
     with IntegrationPatience
     with GuiceOneAppPerSuite
     with WireMockSupport {

  import TeamsAndRepositoriesConnector.Repository

  given HeaderCarrier = HeaderCarrier()

  override lazy val resetWireMockMappings = false

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure(
        "microservice.services.teams-and-repositories.host" -> wireMockHost,
        "microservice.services.teams-and-repositories.port" -> wireMockPort,
        "internal-auth.token" -> "token"
      ).build()

  private val connector = app.injector.instanceOf[TeamsAndRepositoriesConnector]

  "TeamsAndRepositoriesConnector.getRepository" should {
    "correctly parse json response" in {
      stubFor(
        get(urlEqualTo(s"/api/v2/repositories/test-repo"))
          .willReturn(aResponse().withBodyFile("teams-and-repositories/repository.json"))
      )
      val repository = connector.getRepository("test-repo").futureValue
      repository.value.teamNames shouldBe Seq("PlatOps", "Webops")
    }

    "handle 404 - repository not found" in {
      stubFor(
        get(urlEqualTo(s"/api/v2/repositories/non-existing-test-repo"))
          .willReturn(aResponse().withStatus(404))
      )

      val repository = connector.getRepository("non-existing-test-repo").futureValue
      repository shouldBe None
    }
  }

  "TeamsAndRepositoriesConnector.getAllRepositories" should {
    "correctly parse json response" in {
      stubFor(
        get(urlEqualTo("/api/v2/repositories"))
          .willReturn(aResponse().withBodyFile("teams-and-repositories/repositories.json"))
      )

      val repositories = connector.getAllRepositories(archived = None).futureValue
      repositories shouldBe List(
        Repository(
          name           = "test-repo"
        , teamNames      = Seq("PlatOps", "Webops")
        , digitalService = None
        , repoType       = RepoType.Prototype
        , isArchived     = false
        )
        , Repository(
          name           = "another-repo"
        , teamNames      = Seq("PlatOps", "Webops")
        , digitalService = None
        , repoType       = RepoType.Prototype
        , isArchived     = false
        )
      )

      verify(getRequestedFor(urlEqualTo("/api/v2/repositories")))
    }

    "correctly pass query parameter" in {
      stubFor(
        get(urlEqualTo("/api/v2/repositories?archived=false"))
          .willReturn(aResponse().withBodyFile("teams-and-repositories/repositories.json"))
      )

      connector.getAllRepositories(archived = Some(false)).futureValue

      verify(getRequestedFor(urlEqualTo("/api/v2/repositories?archived=false")))
    }
  }
}
