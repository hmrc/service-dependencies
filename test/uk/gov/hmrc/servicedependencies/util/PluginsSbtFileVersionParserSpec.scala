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

package uk.gov.hmrc.servicedependencies.util

import uk.gov.hmrc.servicedependencies.model.Version
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PluginsSbtFileVersionParserSpec extends AnyFreeSpec with Matchers {

  val targetArtifact = "sbt-plugin"

  "Parses sbt-plugin version in line" in {
    val fileContents = """addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.10")}""".stripMargin

    PluginsSbtFileVersionParser.parse(fileContents, targetArtifact) mustBe Some(Version(2, 3, 10))
  }

}
