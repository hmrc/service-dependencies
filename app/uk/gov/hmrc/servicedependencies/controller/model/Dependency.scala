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

package uk.gov.hmrc.servicedependencies.controller.model

import play.api.libs.functional.syntax._
import play.api.libs.json.{JsBoolean, JsString, JsValue, OWrites, Writes, __}
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, Version}


case class Dependency(
    name               : String
  , group              : String
  , currentVersion     : Version
  , latestVersion      : Option[Version]
  , bobbyRuleViolations: List[DependencyBobbyRule]
  , importBy           : Option[ImportedBy] = None
  , scope              : Option[DependencyScope] = None
  )

object Dependency {

  private val scopeWrites = new Writes[DependencyScope] {
    override def writes(s: DependencyScope): JsValue = JsString(s.asString)
  }

  val writes: OWrites[Dependency] =
    ( (__ \ "name"               ).write[String]
    ~ (__ \ "group"              ).write[String]
    ~ (__ \ "currentVersion"     ).write[Version](Version.legacyApiWrites)
    ~ (__ \ "latestVersion"      ).writeNullable[Version](Version.legacyApiWrites)
    ~ (__ \ "bobbyRuleViolations").lazyWrite(Writes.seq[DependencyBobbyRule](DependencyBobbyRule.writes))
    ~ (__ \ "importBy"           ).writeNullable[ImportedBy](ImportedBy.writes)
    ~ (__ \ "scope"              ).writeNullable[DependencyScope](scopeWrites)
    )(unlift(Dependency.unapply))
      .transform( js =>
        js + ("isExternal" -> JsBoolean(!js("group").as[String].startsWith("uk.gov.hmrc")))
      )
}

case class ImportedBy(name: String, group: String, version: Version)

object ImportedBy {
  val writes: OWrites[ImportedBy] =
    ( (__ \ "name"          ).write[String]
    ~ (__ \ "group"         ).write[String]
    ~ (__ \ "currentVersion").write[Version](Version.legacyApiWrites)
    )(unlift(ImportedBy.unapply))
}
