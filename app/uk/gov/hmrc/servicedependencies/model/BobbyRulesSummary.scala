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
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.MongoJavatimeFormats

case class BobbyRulesSummary(
    date   : LocalDate
  , summary: Map[(BobbyRule, SlugInfoFlag), Int]
  )

case class HistoricBobbyRulesSummary(
    date   : LocalDate
  , summary: Map[(BobbyRule, SlugInfoFlag), List[Int]]
  )

private object DataFormat {
  private implicit val brvf = BobbyRule.format

  private def f[A](map: List[(JsValue, Map[String, A])]): Map[(BobbyRule, SlugInfoFlag), A] =
    map.flatMap { case (k1, v1) =>
      v1.map { case (k2, v2) =>
        ( ( k1.as[BobbyRule]
          , SlugInfoFlag.parse(k2).getOrElse(sys.error(s"Invalid SlugInfoFlag $k2")) // TODO propagate failure into client Format
          )
        , v2
        )
      }
    }.toMap

  private def g[A](map: Map[(BobbyRule, SlugInfoFlag), A]): List[(JsValue, Map[String, A])] =
    map.groupBy { case ((r, _), _) => r }
      .map {
      case (r, v1) =>
        ( Json.toJson(r)
        , v1.map { case ((_, f), v2) => (f.asString, v2) }
        )
    }.toList

  def dataFormat[A : Format]: Format[Map[(BobbyRule, SlugInfoFlag), A]] =
    implicitly[Format[List[(JsValue, Map[String, A])]]].inmap(f[A], g[A])
}

object BobbyRulesSummary {
  private implicit val df = DataFormat.dataFormat[Int]

  val apiFormat: OFormat[BobbyRulesSummary] =
    ( (__ \ "date"     ).format[LocalDate]
    ~ (__ \ "summary"  ).format[Map[(BobbyRule, SlugInfoFlag), Int]]
    )(BobbyRulesSummary.apply _, unlift(BobbyRulesSummary.unapply _))

  val mongoFormat: OFormat[BobbyRulesSummary] = {
    implicit val ldf = MongoJavatimeFormats.localDateFormats
    ( (__ \ "date"     ).format[LocalDate]
    ~ (__ \ "summary"  ).format[Map[(BobbyRule, SlugInfoFlag), Int]]
    )(BobbyRulesSummary.apply _, unlift(BobbyRulesSummary.unapply _))
  }
}

object HistoricBobbyRulesSummary {
  private implicit val df = DataFormat.dataFormat[List[Int]]

  val apiFormat: OFormat[HistoricBobbyRulesSummary] =
    ( (__ \ "date"     ).format[LocalDate]
    ~ (__ \ "summary"  ).format[Map[(BobbyRule, SlugInfoFlag), List[Int]]]
    )(HistoricBobbyRulesSummary.apply _, unlift(HistoricBobbyRulesSummary.unapply _))

  def fromBobbyRulesSummary(bobbyRulesSummary: BobbyRulesSummary): HistoricBobbyRulesSummary =
    HistoricBobbyRulesSummary(
        date    = bobbyRulesSummary.date
      , summary = bobbyRulesSummary.summary.mapValues(i => List(i))
      )
}