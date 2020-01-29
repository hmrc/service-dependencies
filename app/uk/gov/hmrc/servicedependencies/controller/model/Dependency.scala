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

package uk.gov.hmrc.servicedependencies.controller.model

import play.api.libs.functional.syntax._
import play.api.libs.json.{OWrites, Writes, __}
import uk.gov.hmrc.servicedependencies.model.Version


case class Dependency(
    name               : String
  , currentVersion     : Version
  , latestVersion      : Option[Version]
  , bobbyRuleViolations: List[DependencyBobbyRule]
  , isExternal         : Boolean = false
  ) {

  // TODO hack since we currently don't store group for dependency
  def group: String =
    name match {
      case "reactivemongo" => "org.reactivemongo"
      case _               => "uk.gov.hmrc"
    }
}

object Dependency {

  val writes: OWrites[Dependency] =
    ( (__ \ "name"               ).write[String]
    ~ (__ \ "currentVersion"     ).write[Version](Version.legacyApiWrites)
    ~ (__ \ "latestVersion"      ).writeNullable[Version](Version.legacyApiWrites)
    ~ (__ \ "bobbyRuleViolations").lazyWrite(Writes.seq[DependencyBobbyRule](DependencyBobbyRule.writes))
    ~ (__ \ "isExternal"         ).write[Boolean]
    )(unlift(Dependency.unapply))
}
