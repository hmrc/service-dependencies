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

import org.scalatest.OptionValues
import play.api.Configuration
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, LibraryConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model.Version
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class CuratedDependencyConfigProviderSpec extends AnyFunSpec with Matchers with OptionValues {

  describe("config loader") {
    it("should load the config") {
      val curatedDependencyConfigProvider =
        new CuratedDependencyConfigProvider(Configuration("curated.config.path" -> "/config/test-config.json"))

      curatedDependencyConfigProvider.curatedDependencyConfig shouldBe CuratedDependencyConfig(
          sbtPlugins = Seq(
              SbtPluginConfig(name = "internal-plugin", group = "uk.gov.hmrc"         , latestVersion = None)
            , SbtPluginConfig(name = "external-plugin", group = "com.example.external", latestVersion = Some(Version("1.4.0")))
            )
        , libraries = List(
            LibraryConfig(name = "lib1", group = "uk.gov.hmrc")
          , LibraryConfig(name = "lib2", group = "uk.gov.hmrc")
          )
        , otherDependencies = Seq(
            OtherDependencyConfig(name = "sbt", group = "org.scala-sbt", latestVersion = Some(Version("0.13.11")))
          )
        )
    }
  }
}
