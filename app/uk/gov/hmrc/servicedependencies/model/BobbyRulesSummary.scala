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
import uk.gov.hmrc.servicedependencies.connector.model.BobbyRule

case class BobbyRulesSummary(
    date   : LocalDate
  , summary: Map[BobbyRule, Map[SlugInfoFlag, List[Int]]]
  )

object BobbyRulesSummary {
  import play.api.libs.json.{__, Format, Json, JsError, JsValue, OFormat, Reads, Writes}
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

  import play.api.libs.functional.syntax._

  private implicit val brvf = BobbyRule.format

  def f(map: List[(JsValue, Map[String, List[Int]])]): Map[BobbyRule, Map[SlugInfoFlag, List[Int]]] =
    map.map { case (k1, v1) => ( k1.as[BobbyRule]
                                , v1.map { case (k2, v2) => ( SlugInfoFlag.parse(k2).getOrElse(sys.error("TODO handle failure"))
                                                            , v2
                                                            )
                                        }
                                )
            }.toMap

  def g(map: Map[BobbyRule, Map[SlugInfoFlag, List[Int]]]): List[(JsValue, Map[String, List[Int]])] =
    map.map { case (k1, v1) => ( Json.toJson(k1)
                                , v1.map { case (k2, v2) => ( k2.asString, v2) }
                                )
            }.toList

  val apiFormat: OFormat[BobbyRulesSummary] =
    ( (__ \ "date"     ).format[LocalDate]
    ~ (__ \ "summary"  ).format[List[(JsValue, Map[String, List[Int]])]].inmap(f, g)
    )(BobbyRulesSummary.apply _, unlift(BobbyRulesSummary.unapply _))

  val mongoFormat: OFormat[BobbyRulesSummary] = {
    // TODO ReactiveMongoFormats only support joda.time, not java.time...
    //import ReactiveMongoFormats.localDateFormats
    implicit val localDateRead: Reads[LocalDate] =
      (__ \ "$date").read[Long].map { date =>
        LocalDate.ofEpochDay(date / (24 * 60 * 60 * 1000))
      }

    implicit val localDateWrite: Writes[LocalDate] = new Writes[LocalDate] {
      def writes(localDate: LocalDate): JsValue = Json.obj(
        "$date" -> localDate.toEpochDay * 24 * 60 * 60 * 1000
      )
    }

    implicit val localDateFormats = Format(localDateRead, localDateWrite)

    ( (__ \ "date"     ).format[LocalDate]
    ~ (__ \ "summary"  ).format[List[(JsValue, Map[String, List[Int]])]].inmap(f, g)
    )(BobbyRulesSummary.apply _, unlift(BobbyRulesSummary.unapply _))
  }
}
