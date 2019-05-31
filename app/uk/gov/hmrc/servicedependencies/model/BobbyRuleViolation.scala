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

import uk.gov.hmrc.servicedependencies.connector.model.BobbyRule
import play.api.libs.functional.syntax._
import play.api.libs.json.{OWrites, __}


case class BobbyRuleViolation(rule:BobbyRule, count: Int)

object BobbyRuleViolation {
  val writes: OWrites[BobbyRuleViolation] = {
    implicit val brw = BobbyRule.writes
    (   (__ \ "rule" ).write[BobbyRule]
      ~ (__ \ "count").write[Int]
      )(unlift(BobbyRuleViolation.unapply))
  }
}