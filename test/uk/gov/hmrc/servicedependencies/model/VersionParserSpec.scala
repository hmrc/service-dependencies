/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.model

import org.scalatest.{FreeSpec, MustMatchers}
import uk.gov.hmrc.servicedependencies.util.VersionParser

class VersionParserSpec extends FreeSpec with MustMatchers {

  val targetArtifact = "play-frontend"

  "Parses play-frontend version in line" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile, targetArtifact) mustBe Some(Version(1, 2, 3))
  }

  "Parses play-frontend version in line with scope after" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3" % scope classifier "tests",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile, targetArtifact) mustBe Some(Version(1, 2, 3))
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

    VersionParser.parse(buildFile, targetArtifact) mustBe Some(Version(1, 2, 3))
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

    VersionParser.parse(buildFile, targetArtifact) mustBe Some(Version(1, 2, 3))
  }

  "Returns None if it cannot find a play-frontend version" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "org.pegdown" % "pegdown" % "1.4.2" % scope
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile, targetArtifact) mustBe None
  }

  "Parses multiple artifacts together" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3",
                      |        "uk.gov.hmrc" %% "play-backend" % "3.5.5",
                      |        "uk.gov.hmrc" %% "play-middle" % "6.8.8"
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile, Seq("play-frontend", "play-backend", "play-middle")) must contain theSameElementsAs Seq(
      "play-frontend" -> Some(Version(1, 2, 3)), "play-backend" -> Some(Version(3, 5, 5)), "play-middle" -> Some(Version(6, 8, 8))
    )
  }

  "Parses multiple artifacts and return None for any dependency not present" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3",
                      |        "uk.gov.hmrc" %% "play-backend" % "3.5.5",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile, Seq("play-frontend", "play-backend", "play-middle")) must contain theSameElementsAs Seq(
      "play-frontend" -> Some(Version(1, 2, 3)), "play-backend" -> Some(Version(3, 5, 5)), "play-middle" -> None
    )
  }

  "Parses release version correctly" in {
    val tag = "release/1.0.1"
    VersionParser.parseReleaseVersion("release/", tag) mustBe Some(Version(1, 0, 1))
  }

  "Parsing an invalid release version returns None" in {
    val tag = "release/1.0.1"
    VersionParser.parseReleaseVersion("non-release/", tag) mustBe None
  }

  "Parsing a build.properties file returns the sbt version" in {
    VersionParser.parsePropertyFile("sbt.version=1.2.3", "sbt.version") mustBe Some(Version(1,2,3))
    VersionParser.parsePropertyFile(" sbt.version = 1.2.3 ", "sbt.version") mustBe Some(Version(1,2,3))
  }

  "Parsing build.properties file returns None for sbt version if the 'sbt.version' is not defined" in {
    VersionParser.parsePropertyFile("some.non-related.key=1.2.3", "sbt.version") mustBe None
  }

}
