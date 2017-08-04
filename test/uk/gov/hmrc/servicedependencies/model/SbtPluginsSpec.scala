package uk.gov.hmrc.servicedependencies.model

import org.scalatest.{FreeSpec, Matchers}

class SbtPluginsSpec extends FreeSpec with Matchers {

  "SbtPlugins" - {
    "should correctly identify internal repositories" in {
      SbtPlugins("uk.gov.hmrc", "something", None).isInternal() shouldBe true
      SbtPlugins("uk.gov.homeoffice", "something", None).isInternal() shouldBe false
    }
  }

}
