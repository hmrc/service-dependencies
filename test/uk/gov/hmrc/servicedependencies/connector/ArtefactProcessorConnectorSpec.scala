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

import java.time.Instant

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.servicedependencies.model.{JavaInfo, MetaArtefact, MetaArtefactModule, SlugInfo, Version}

class ArtefactProcessorConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterAll
     with GuiceOneAppPerSuite
     with MockitoSugar
     with WireMockSupport {

  given HeaderCarrier = HeaderCarrier()

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure(
        "microservice.services.artefact-processor.host" -> wireMockHost,
        "microservice.services.artefact-processor.port" -> wireMockPort,
        "internal-auth.token" -> "token"
      )
      .build()

  private val connector = app.injector.instanceOf[ArtefactProcessorConnector]

  "ArtefactProcessorConnector.getMetaArtefact" should {
    "correctly parse json response" in {
      stubFor(
        get(urlEqualTo(s"/result/meta/name/1.0.0"))
          .willReturn(aResponse().withBodyFile("artefact-processor/meta-artefact.json"))
      )

      connector.getMetaArtefact("name", Version("1.0.0")).futureValue shouldBe Some(
        MetaArtefact(
          name               = "my-library",
          version            = Version("1.0.0"),
          uri                = "https://store/meta/my-meta/my-library-v1.0.0.meta.tgz",
          gitUrl             = Some("https://github.com/hmrc/my-library.git"),
          dependencyDotBuild = Some("dependencyDotBuild"),
          buildInfo          = Map("k" -> "v"),
          modules            = Seq(MetaArtefactModule(
                                 name                  = "module-1",
                                 group                 = "uk.gov.hmrc",
                                 sbtVersion            = Some(Version("1.4.9")),
                                 crossScalaVersions    = Some(List(Version("2.12.14"))),
                                 publishSkip           = Some(false),
                                 dependencyDotCompile  = Some("dependencyDotCompile"),
                                 dependencyDotProvided = Some("dependencyDotProvided"),
                                 dependencyDotTest     = Some("dependencyDotTest"),
                                 dependencyDotIt       = Some("dependencyDotIt")
                               )),
          created            = Instant.parse("2022-01-04T17:46:18.588Z")
        )
      )
    }
  }

  "ArtefactProcessorConnector.getSlugInfo" should {
    "correctly parse json response" in {
      stubFor(
        get(urlEqualTo(s"/result/slug/name/1.0.0"))
          .willReturn(aResponse().withBodyFile("artefact-processor/slug-info.json"))
      )

      connector.getSlugInfo("name", Version("1.0.0")).futureValue shouldBe Some(
        SlugInfo(
          created               = Instant.parse("2019-06-28T11:51:23Z"),
          uri                   = "https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz",
          name                  = "my-slug",
          version               = Version.apply("0.27.0"),
          runnerVersion         = "0.5.2",
          classpath             = "some-classpath",
          java                  = JavaInfo("1.181.0", "Oracle", "JDK"),
          sbtVersion            = Some("1.4.9"),
          repoUrl               = Some("https://github.com/hmrc/test.git"),
          applicationConfig     = "some-application-config",
          slugConfig            = "some-slug-config",
          teams                 = List.empty
        )
      )
    }
  }
}
