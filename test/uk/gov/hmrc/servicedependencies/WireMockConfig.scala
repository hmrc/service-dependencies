package uk.gov.hmrc.servicedependencies

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.{MappingBuilder, WireMock}
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._

class WireMockConfig(stubPort: Int) {
  val stubHost = "localhost"
  var wireMockServer: WireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  def host() = s"http://$stubHost:$stubPort"

  def start() = {
    if (!wireMockServer.isRunning) {
      wireMockServer.start()
      WireMock.configureFor(stubHost, stubPort)
    }
  }

  def stop() = {
    wireMockServer.stop()
  }

  def stub(mapping: MappingBuilder) = {
    wireMockServer.stubFor(mapping)
  }
}