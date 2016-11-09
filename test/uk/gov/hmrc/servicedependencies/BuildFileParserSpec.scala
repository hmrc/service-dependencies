/*
 * Copyright 2016 HM Revenue & Customs
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

import org.scalatest.{FreeSpec, MustMatchers}

class BuildFileParserSpec extends FreeSpec with MustMatchers {

  val targetArtifact = "play-frontend"

  "Parses play-frontend version in line" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    BuildFileVersionParser.parse(buildFile, targetArtifact) mustBe Some(Version(1, 2, 3))
  }

  "Parses play-frontend version in line with scope after" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3" % scope classifier "tests",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    BuildFileVersionParser.parse(buildFile, targetArtifact) mustBe Some(Version(1, 2, 3))
  }

  "Parses play-frontend version form variable" in {
    val buildFile = """  object Test {
                      |   val pFV = "1.2.3"
                      |
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % pFV,
                      |      )
                      |    }.test
                      |  }""".stripMargin

    BuildFileVersionParser.parse(buildFile, targetArtifact) mustBe Some(Version(1, 2, 3))
  }

  "Parses play-frontend version form variable with scope" in {
    val buildFile = """  object Test {
                      |   val pFV = "1.2.3"
                      |
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % pFV % scope,
                      |      )
                      |    }.test
                      |  }""".stripMargin

    BuildFileVersionParser.parse(buildFile, targetArtifact) mustBe Some(Version(1, 2, 3))
  }

  "Returns None if it cannot find a play-frontend version" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "org.pegdown" % "pegdown" % "1.4.2" % scope
                      |      )
                      |    }.test
                      |  }""".stripMargin

    BuildFileVersionParser.parse(buildFile, targetArtifact) mustBe None
  }
}
