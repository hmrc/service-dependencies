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
