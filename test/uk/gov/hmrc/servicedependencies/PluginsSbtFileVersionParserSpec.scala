package uk.gov.hmrc.servicedependencies

import org.scalatest.{FreeSpec, MustMatchers}

class PluginsSbtFileVersionParserSpec extends FreeSpec with MustMatchers {

  val targetArtifact = "sbt-plugin"

  "Parses sbt-plugin version in line" in {
    val fileContents = """addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.10")}""".stripMargin

    PluginsSbtFileVersionParser.parse(fileContents, targetArtifact) mustBe Some(Version(2, 3, 10))
  }

}