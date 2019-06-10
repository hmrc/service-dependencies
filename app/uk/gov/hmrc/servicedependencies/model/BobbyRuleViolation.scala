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

import java.time.LocalDate
import play.api.libs.functional.syntax._
import play.api.libs.json.{OFormat, __}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.servicedependencies.connector.model.BobbyRule


case class BobbyRuleViolation(rule: BobbyRule, count: Int)

object BobbyRuleViolation {
  val format: OFormat[BobbyRuleViolation] = {
    implicit val brf = BobbyRule.format
    ( (__ \ "rule" ).format[BobbyRule]
    ~ (__ \ "count").format[Int]
    )(BobbyRuleViolation.apply, unlift(BobbyRuleViolation.unapply))
  }
}

case class BobbyRulesSummary(
    date: LocalDate
  , summary: Map[SlugInfoFlag, Seq[BobbyRuleViolation]]
  )

object BobbyRulesSummary {
  import play.api.libs.json.{__, Json, JsValue, JsError, Reads}
  import play.api.libs.functional.syntax._

  val apiFormat: OFormat[BobbyRulesSummary] = {
    implicit val brvf = BobbyRuleViolation.format

    def f(map: Map[String, Seq[JsValue]]): Map[SlugInfoFlag, Seq[BobbyRuleViolation]] =
      map.map { case (k, v) =>  (SlugInfoFlag.parse(k).getOrElse(sys.error("TODO handle failure")), v.map(_.as[BobbyRuleViolation])) }

    def g(map: Map[SlugInfoFlag, Seq[BobbyRuleViolation]]): Map[String, Seq[JsValue]] =
      map.map { case (k, v) =>  (k.asString, v.map(Json.toJson(_))) }

    ( (__ \ "date"     ).format[LocalDate]
    ~ (__ \ "summary"  ).format[Map[String, Seq[JsValue]]].inmap(f, g)
    )(BobbyRulesSummary.apply _, unlift(BobbyRulesSummary.unapply _))
  }

  val mongoFormat: OFormat[BobbyRulesSummary] = {
    import ReactiveMongoFormats.localDateFormats // TODO confirm this is being used...
    implicit val brvf = BobbyRuleViolation.format

    def f(map: Map[String, Seq[JsValue]]): Map[SlugInfoFlag, Seq[BobbyRuleViolation]] =
      map.map { case (k, v) =>  (SlugInfoFlag.parse(k).getOrElse(sys.error("TODO handle failure")), v.map(_.as[BobbyRuleViolation])) }

    def g(map: Map[SlugInfoFlag, Seq[BobbyRuleViolation]]): Map[String, Seq[JsValue]] =
      map.map { case (k, v) =>  (k.asString, v.map(Json.toJson(_))) }

    ( (__ \ "date"     ).format[LocalDate]
    ~ (__ \ "summary"  ).format[Map[String, Seq[JsValue]]].inmap(f, g)
    )(BobbyRulesSummary.apply _, unlift(BobbyRulesSummary.unapply _))
  }
}
