package uk.gov.hmrc.servicedependencies

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, MustMatchers}
import org.scalatestplus.play.OneAppPerSuite

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
    stubServices()
  }

  private def stubRepositories(repositoryName: String) = {
    wireMock.stub(get(urlEqualTo(s"/repositories/$repositoryName"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(loadFileAsString(s"/teams-and-repositories/repository.json"))))
  }

  private def stubServices() = {
    wireMock.stub(get(urlEqualTo(s"/services?teamDetails=true"))
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

}
