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

package uk.gov.hmrc.servicedependencies.config

import com.typesafe.config.ConfigFactory
import org.mockito.Mockito.when
import org.mockito.ArgumentMatchers.any
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.model.Version

class ServiceDependenciesConfigTest
  extends AnyWordSpec
     with Matchers
     with MockitoSugar {

  "ServiceDependenciesConfig" should {
    "load the curatedDependencyConfig" in {
      val config =
        Configuration(
          "curated.config.path" -> "/config/test-config.json"
        ).withFallback(Configuration(ConfigFactory.load()))

      val serviceConfig = mock[ServicesConfig]
      when(serviceConfig.baseUrl(any())).thenReturn("")

      val sdc = ServiceDependenciesConfig(config, serviceConfig)

      sdc.curatedDependencyConfig shouldBe CuratedDependencyConfig(
          sbtPlugins = List(
                         DependencyConfig(name = "internal-plugin", group = "uk.gov.hmrc"         , latestVersion = None)
                       , DependencyConfig(name = "external-plugin", group = "com.example.external", latestVersion = Some(Version("1.4.0")))
                       )
        , libraries  = List(
                         DependencyConfig(name = "lib1", group = "uk.gov.hmrc", latestVersion = Some(Version("1.4.1")))
                       , DependencyConfig(name = "lib2", group = "uk.gov.hmrc", latestVersion = None)
                       )
        , others     = List(
                         DependencyConfig(name = "sbt", group = "org.scala-sbt", latestVersion = Some(Version("0.13.11")))
                       )
        )
    }
  }
}
