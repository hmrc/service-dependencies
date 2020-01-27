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

package uk.gov.hmrc.servicedependencies.model

import java.time.LocalDate
import play.api.data.format.Formats
import play.api.libs.functional.syntax._
import play.api.libs.json.{Reads, OFormat, Writes, __}
import uk.gov.hmrc.http.controllers.RestFormats
import uk.gov.hmrc.servicedependencies.controller.model.DependencyBobbyRule


final case class BobbyVersion(
    version  : Version
  , inclusive: Boolean
  )


final case class BobbyRule(
  organisation: String,
  name        : String,
  range       : BobbyVersionRange,
  reason      : String,
  from        : LocalDate) {

  def asDependencyBobbyRule: DependencyBobbyRule =
    DependencyBobbyRule(
      reason = this.reason,
      from   = this.from,
      range  = this.range
    )
}


object BobbyRule {
  val format: OFormat[BobbyRule] = {
    implicit val ldw  = Formats.localDateFormat
    implicit val bvwf = BobbyVersionRange.format
    ( (__ \ "organisation").format[String]
    ~ (__ \ "name"        ).format[String]
    ~ (__ \ "range"       ).format[BobbyVersionRange]
    ~ (__ \ "reason"      ).format[String]
    ~ (__ \ "from"        ).format[LocalDate]
    )(BobbyRule.apply, unlift(BobbyRule.unapply))
  }
}
