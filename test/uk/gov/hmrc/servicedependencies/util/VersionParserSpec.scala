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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.servicedependencies.model.{GithubDependency, Version}

class VersionParserSpec extends AnyFreeSpec with Matchers {

  "Parses play-frontend version in line" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile) mustBe Seq(
      GithubDependency(name = "play-frontend", group = "uk.gov.hmrc", version = Version("1.2.3"))
    )
  }

  "Parses play-frontend version in line with scope after" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3" % scope classifier "tests",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile) mustBe Seq(
      GithubDependency(name = "play-frontend", group = "uk.gov.hmrc", version = Version("1.2.3"))
    )
  }

  "Parses play-frontend version including suffix in line with scope after" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "play-frontend" % "1.2.3-play-26" % scope classifier "tests",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile) mustBe Seq(
      GithubDependency(name = "play-frontend", group = "uk.gov.hmrc", version = Version("1.2.3-play-26"))
    )
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

    VersionParser.parse(buildFile) mustBe Seq(
      GithubDependency(name = "play-frontend", group = "uk.gov.hmrc", version = Version("1.2.3"))
    )
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

    VersionParser.parse(buildFile) mustBe Seq(
      GithubDependency(name = "play-frontend", group = "uk.gov.hmrc", version = Version("1.2.3"))
    )
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

    VersionParser.parse(buildFile) mustBe Seq(
      GithubDependency(name = "play-frontend", group = "uk.gov.hmrc", version = Version("1.2.3"))
    , GithubDependency(name = "play-backend" , group = "uk.gov.hmrc", version = Version("3.5.5"))
    , GithubDependency(name = "play-middle"  , group = "uk.gov.hmrc", version = Version("6.8.8"))
    )
  }

  "Parsing version ending with play version returns correct version" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "simple-reactivemongo" % "7.0.0-play-26",
                      |      )
                      |    }.test
                      |  }""".stripMargin

    VersionParser.parse(buildFile) mustBe Seq(
      GithubDependency(name = "simple-reactivemongo", group = "uk.gov.hmrc", version = Version("7.0.0-play-26"))
    )
  }

  "Parsing version from variable ending with play version returns correct version" in {
      val buildFile = """  object Test {
                        |    val mongoVersion = "7.0.0-play-26"
                        |    def apply() = new TestDependencies {
                        |      override lazy val test = Seq(
                        |        "uk.gov.hmrc" %% "simple-reactivemongo" % mongoVersion,
                        |      )
                        |    }.test
                        |  }""".stripMargin

    VersionParser.parse(buildFile) mustBe Seq(
      GithubDependency(name = "simple-reactivemongo", group = "uk.gov.hmrc", version = Version("7.0.0-play-26"))
    )
  }

  "Parsing non semantic version number returns None" in {
    val buildFile = """  object Test {
                      |    def apply() = new TestDependencies {
                      |      override lazy val test = Seq(
                      |        "uk.gov.hmrc" %% "library" % "x7.22-alpha",
                      |      )
                      |    }.test
                      |  }""".stripMargin
    VersionParser.parse(buildFile) mustBe Seq.empty
  }


  "Parses sbt-plugin version in line" in {
    val fileContents = """addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.10")}""".stripMargin

    VersionParser.parse(fileContents) mustBe Seq(
      GithubDependency(name = "sbt-plugin", group = "com.typesafe.play", version = Version("2.3.10"))
    )
  }

  "Parsing a build.properties file containing only the sbt version returns the sbt version" in {
    VersionParser.parsePropertyFile("sbt.version=1.2.3", "sbt.version") mustBe Some(Version("1.2.3"))
    VersionParser.parsePropertyFile(" sbt.version = 1.2.3 ", "sbt.version") mustBe Some(Version("1.2.3"))
  }

  "Parsing a build.properties file containing additional keys returns the sbt version" - {
    "when the sbt version is the first entry" in {
      val buildProperties = s"""|sbt.version=0.13.17
                                |hmrc-frontend-scaffold.version=0.4.1
                                |""".stripMargin

      VersionParser.parsePropertyFile(buildProperties, "sbt.version") mustBe Some(Version("0.13.17"))
    }

    "when the sbt version is the last entry" in {
      val buildProperties = s"""|hmrc-frontend-scaffold.version=0.4.1
                                |sbt.version=0.13.17
                                |""".stripMargin

      VersionParser.parsePropertyFile(buildProperties, "sbt.version") mustBe Some(Version("0.13.17"))
    }
  }

  "Parsing build.properties file returns None for sbt version if the 'sbt.version' is not defined" in {
    VersionParser.parsePropertyFile("some.non-related.key=1.2.3", "sbt.version") mustBe None
  }
}
