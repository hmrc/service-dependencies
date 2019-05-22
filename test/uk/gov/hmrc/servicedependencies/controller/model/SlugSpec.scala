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

package uk.gov.hmrc.servicedependencies.controller.model

import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.servicedependencies.controller.model.SlugFactory.aSlug
import uk.gov.hmrc.servicedependencies.model.SlugDependency

class SlugSpec extends FlatSpec with Matchers {

    it should "correctly deserialize" in {
      implicit val sdWriter: Writes[SlugDependency] = Json.writes[SlugDependency]
      val slugWriter = Json.writes[Slug]
      val json = Json.parse(s"""{
                   |  "applicationConfig": "${aSlug.applicationConfig}",
                   |  "classpath": "${aSlug.classpath}",
                   |  "dependencies": ${Json.toJson(aSlug.dependencies)},
                   |  "jdkVersion": "${aSlug.jdkVersion}",
                   |  "latest": ${aSlug.latest},
                   |  "name": "${aSlug.name}",
                   |  "runnerVersion": "${aSlug.runnerVersion}",
                   |  "slugConfig": "${aSlug.slugConfig}",
                   |  "teams": ${Json.toJson(aSlug.teams)},
                   |  "uri": "${aSlug.uri}",
                   |  "version": "${aSlug.version}"
                   |}""".stripMargin)
      Json.toJson(aSlug)(slugWriter) shouldBe json
    }

}
