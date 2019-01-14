/*
 * Copyright 2019 HM Revenue & Customs
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

import org.scalatest.{FunSpec, Matchers, OptionValues}
import play.api.Configuration
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model.Version

class CuratedDependencyConfigProviderSpec extends FunSpec with Matchers with OptionValues {

  describe("config loader") {
    it("should load the config") {
      val curatedDependencyConfigProvider =
        new CuratedDependencyConfigProvider(Configuration("curated.config.path" -> "/config/test-config.json"))

      curatedDependencyConfigProvider.curatedDependencyConfig shouldBe CuratedDependencyConfig(
        sbtPlugins = Seq(
          SbtPluginConfig("uk.gov.hmrc", "internal-plugin", None),
          SbtPluginConfig("com.example.external", "external-plugin", Some(Version("1.4.0")))
        ),
        libraries = List(
          "lib1",
          "lib2"
        ),
        otherDependencies = Seq(OtherDependencyConfig("sbt", Some(Version("0.13.11"))))
      )
    }
  }
}
