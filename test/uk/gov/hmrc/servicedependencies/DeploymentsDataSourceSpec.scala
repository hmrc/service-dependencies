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

  "Retrieving a list of running services" - {
    "correctly parse json response" in {
      val services = new DeploymentsDataSource(new ReleasesConfig {
        override def releasesServiceUrl: String = wireMock.host()
      })

      val runningServices = services.listOfRunningServices().futureValue
      runningServices.length mustBe 162
    }
  }
}
