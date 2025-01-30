/*
 * Copyright 2023 HM Revenue & Customs
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

import java.time.LocalDate
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, DependencyBobbyRule}


case class BobbyVersion(
  version  : Version
, inclusive: Boolean
)


case class BobbyRule(
  organisation  : String,
  name          : String,
  range         : BobbyVersionRange,
  reason        : String,
  from          : LocalDate,
  exemptProjects: Seq[String] = Seq.empty
):
  def asDependencyBobbyRule: DependencyBobbyRule =
    DependencyBobbyRule(
      reason         = this.reason,
      from           = this.from,
      range          = this.range,
      exemptProjects = this.exemptProjects
    )


object BobbyRule:
  val format: Format[BobbyRule] =
    given Format[BobbyVersionRange] = BobbyVersionRange.format
    ( (__ \ "organisation"  ).format[String]
    ~ (__ \ "name"          ).format[String]
    ~ (__ \ "range"         ).format[BobbyVersionRange]
    ~ (__ \ "reason"        ).format[String]
    ~ (__ \ "from"          ).format[LocalDate]
    ~ (__ \ "exemptProjects").formatWithDefault[Seq[String]](Seq.empty)
    )(BobbyRule.apply, br => Tuple.fromProductTyped(br))

case class BobbyRules(
  asMap: Map[(String, String), List[BobbyRule]]
):
  def violationsFor(dependency: Dependency, repoName: String): List[DependencyBobbyRule] =
    violationsFor(dependency.group, dependency.name, dependency.currentVersion, repoName)

  def violationsFor(group: String, name: String, version: Version, repoName: String): List[DependencyBobbyRule] =
    asMap
      .getOrElse((group, name), Nil)
      .filter(_.range.includes(version))
      .filterNot(_.exemptProjects.contains(repoName))
      .map(_.asDependencyBobbyRule)
