/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.config

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.model.Version

class ServiceDependenciesConfigTest extends AnyFunSpec with Matchers with MockitoSugar with OptionValues {

  describe("ServiceDependenciesConfig") {

    it("should load github credentials from config, when available") {

      val config = Configuration(
        "github.open.api.host" -> "https://api.test.url",
        "github.open.api.user" -> "testuser",
        "github.open.api.key"  -> "key123")

      val serviceConfig = mock[ServicesConfig]
      when(serviceConfig.baseUrl(any())).thenReturn("")

      val sdc = new ServiceDependenciesConfig(config, serviceConfig)

      sdc.githubApiOpenConfig.key shouldBe "key123"
      sdc.githubApiOpenConfig.user shouldBe "testuser"
      sdc.githubApiOpenConfig.apiUrl shouldBe "https://api.test.url"
    }

    it("should load the curatedDependencyConfig") {
      val config =
        Configuration("curated.config.path" -> "/config/test-config.json")


      val serviceConfig = mock[ServicesConfig]
      when(serviceConfig.baseUrl(any())).thenReturn("")

      val sdc = new ServiceDependenciesConfig(config, serviceConfig)

      sdc.curatedDependencyConfig shouldBe CuratedDependencyConfig(
          sbtPlugins = List(
                         DependencyConfig(name = "internal-plugin", group = "uk.gov.hmrc"         , latestVersion = None)
                       , DependencyConfig(name = "external-plugin", group = "com.example.external", latestVersion = Some(Version("1.4.0")))
                       )
        , libraries  = List(
                         DependencyConfig(name = "lib1", group = "uk.gov.hmrc", latestVersion = None)
                       , DependencyConfig(name = "lib2", group = "uk.gov.hmrc", latestVersion = None)
                       )
        , others     = List(
                         DependencyConfig(name = "sbt", group = "org.scala-sbt", latestVersion = Some(Version("0.13.11")))
                       )
        )
    }
  }
}
