/*
 * Copyright 2021 HM Revenue & Customs
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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsError, Json}

class TeamSpec extends AnyFlatSpec with Matchers {

  implicit val tf = Team.format

  "Given correctly formatted JSON, creating a team with repositories" should "return expected Team" in {
    val json = Json.parse(
      """{
        |  "name": "Team A",
        |  "repos": { "Service": [ "Service A", "Service B" ], "Library": [ "Library A" ] }
        |}""".stripMargin)

    json.as[Team] shouldBe Team("Team A", Map("Service" -> Seq("Service A", "Service B"), "Library" -> Seq("Library A")))
  }

  "Given correctly formatted JSON, creating a team without repositories" should "return expected Team" in {
    val json = Json.parse("""{"name": "Team A"}""")

    json.as[Team] shouldBe Team("Team A", Map.empty)
  }

  "Given incorrectly formatted JSON, creating a team" should "return expected wrapped error" in {
    val json = Json.parse("""{ "badKey" : "Nonsense value" }""")

    json.validate[Team].isInstanceOf[JsError] shouldBe true
  }
}