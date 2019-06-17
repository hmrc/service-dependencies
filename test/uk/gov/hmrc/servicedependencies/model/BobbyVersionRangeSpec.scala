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

package uk.gov.hmrc.servicedependencies.model

import org.scalatest.{FlatSpec, Matchers}

class BobbyVersionRangeSpec extends FlatSpec with Matchers {

  "A BobbyVersionRange" should "read (,1.0] as 'x <= 1.0'" in {

    val range = BobbyVersionRange("(,1.0.0]")

    range.lowerBound shouldBe None
    range.upperBound shouldBe Some(BobbyVersion(Version("1.0.0"), inclusive = true))
    range.qualifier  shouldBe None
  }

  it should "read [1.0.0] as fixed version '1.0.0'" in {

    val range = BobbyVersionRange("[1.0.0]")

    range.lowerBound shouldBe Some(BobbyVersion(Version("1.0.0"), inclusive = true))
    range.upperBound shouldBe Some(BobbyVersion(Version("1.0.0"), inclusive = true))
    range.qualifier  shouldBe None

  }

  it should "read [1.2.0,1.3.0] as 1.2.0 <= x <= 1.3.0" in {

    val range = BobbyVersionRange("[1.2.0,1.3.0]")

    range.lowerBound shouldBe Some(BobbyVersion(Version("1.2.0"), inclusive = true))
    range.upperBound shouldBe Some(BobbyVersion(Version("1.3.0"), inclusive = true))
    range.qualifier  shouldBe None

  }

  it should "read [1.0.0,2.0.0) as 1.0.0 <= x < 2.0.0" in {

    val range = BobbyVersionRange("[1.0.0,2.0.0)")

    range.lowerBound shouldBe Some(BobbyVersion(Version("1.0.0"), inclusive = true))
    range.upperBound shouldBe Some(BobbyVersion(Version("2.0.0"), inclusive = false))
    range.qualifier  shouldBe None
  }

  it should "read [8.0.0,8.4.1] as 8.0.0 <= x <= 8.4.1" in {

    val range = BobbyVersionRange("[8.0.0,8.4.1]")

    range.lowerBound shouldBe Some(BobbyVersion(Version("8.0.0"), inclusive = true))
    range.upperBound shouldBe Some(BobbyVersion(Version("8.4.1"), inclusive = true))
    range.qualifier  shouldBe None

  }

  it should "read ranges with spaces" in {

    val range = BobbyVersionRange("[8.0.0, 8.4.1]")

    range.lowerBound shouldBe Some(BobbyVersion(Version("8.0.0"), inclusive = true))
    range.upperBound shouldBe Some(BobbyVersion(Version("8.4.1"), inclusive = true))
    range.qualifier  shouldBe None

  }

  it should "read [1.5.0,) as x >= 1.5.0" in {

    val range = BobbyVersionRange("[1.5.0,)")

    range.lowerBound shouldBe Some(BobbyVersion(Version("1.5.0"), inclusive = true))
    range.upperBound shouldBe None
    range.qualifier  shouldBe None

  }

  it should "read the '[*-SNAPSHOT]' range'" in {
    val range = BobbyVersionRange("[*-SNAPSHOT]")

    range.lowerBound shouldBe None
    range.upperBound shouldBe None
    range.qualifier  shouldBe Some("SNAPSHOT")

  }

  it should "fail to parse when incomplete version is provided" in {
    BobbyVersionRange.parse("[1.5,)") shouldBe None
  }

  it should "fail to parse when brackets are missing" in {
    BobbyVersionRange.parse("1.5,)") shouldBe None
    BobbyVersionRange.parse("[1.5,") shouldBe None
    BobbyVersionRange.parse("1.5")   shouldBe None
  }

  it should "fail to parse when the range is open on both sides" in {
    BobbyVersionRange.parse("(,1.5,)") shouldBe None
  }

  it should "fail to parse when multiple sets are used" in { // should we support this? Is valid
    BobbyVersionRange.parse("(,1.0],[1.2,)") shouldBe None
  }

  it should "fail to parse when qualifier is not defined" in {
    BobbyVersionRange.parse("[*-]") shouldBe None
    BobbyVersionRange.parse("[*]")  shouldBe None
  }

  it should "include 1.2.5 when the expression is [1.2.0,1.3.0]" in {
    BobbyVersionRange("[1.2.0,1.3.0]").includes(Version("1.2.5")) shouldBe true
  }

  it should "include 2.3 when the expression is (,2.4]" in {
    BobbyVersionRange("(,2.4.0]").includes(Version("2.3")) shouldBe true
  }

  it should "include 0.2.0 when the expression is (,1.0.0]" in {
    BobbyVersionRange("(,1.0.0]").includes(Version("0.2.0")) shouldBe true
  }

  it should "include 1.2.0 when the expression is [1.0.0,)" in {
    BobbyVersionRange("[1.0.0,)").includes(Version("1.2.0")) shouldBe true
  }

  it should "not include the left boundary when the expression is (1.0.0,)" in {
    BobbyVersionRange("(1.0.0,)").includes(Version("1.0.0")) shouldBe false
  }

  it should "not include the right boundary when the expression is (,1.0.0)" in {
    BobbyVersionRange("(,1.0.0)").includes(Version("1.0.0")) shouldBe false
  }

  it should "include snapshots when the version is 1.0.0-SNAPSHOT" in {
    BobbyVersionRange("[*-SNAPSHOT]").includes(Version("1.0.0-SNAPSHOT")) shouldBe true
  }

  it should "not include snapshots when the version is 1.0.0" in {
    BobbyVersionRange("[*-SNAPSHOT]").includes(Version("1.0.0")) shouldBe false
  }

  it should "build string that contains inclusive lower-bound and upper-bound" in {
    BobbyVersionRange("[1.2.0,1.3.0]").toString shouldBe "[1.2.0,1.3.0]"
  }

  it should "build string that contains wildcard" in {
    BobbyVersionRange("[*-SNAPSHOT]").toString shouldBe "[*-SNAPSHOT]"
  }

  it should "build string that contains non-inclusive lower-bound and upper-bound" in {
    BobbyVersionRange("(1.2.0,1.3.0)").toString shouldBe "(1.2.0,1.3.0)"
  }

  it should "compare multidigit versions" in {
    BobbyVersionRange("[1.0.0, 1.2.0]").includes(Version("1.12.0")) shouldBe false
  }

  it should "understand play cross compiled libraries and ignore play suffixes" in {
    BobbyVersionRange("[1.0.0, 1.0.0]").includes(Version("1.0.0-play-25")) shouldBe true
    BobbyVersionRange("[1.0.0, 1.0.0]").includes(Version("1.0.0-play-26")) shouldBe true
  }
}
