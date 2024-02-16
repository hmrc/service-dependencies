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

package uk.gov.hmrc.servicedependencies.binders

import play.api.mvc.QueryStringBindable
import uk.gov.hmrc.servicedependencies.model.BobbyRuleQuery

import java.time.LocalDate
import scala.util.Try

object Binders {
  
  implicit def bobbyRuleQueryStringBindable(implicit stringBinder: QueryStringBindable[String]): QueryStringBindable[BobbyRuleQuery] =
    new QueryStringBindable[BobbyRuleQuery] {

      private def stringToBobby(key: String, rule: String): Either[String, BobbyRuleQuery] =
        rule.split(":") match {
          case Array(organisation, name, range) => Right(BobbyRuleQuery(organisation, name, range))
          case _                                => Left(s"Invalid $key")
        }

      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, BobbyRuleQuery]] =
        stringBinder.bind(key, params)
          .map(_.flatMap(stringToBobby(key, _)))

      override def unbind(key: String, value: BobbyRuleQuery): String =
        stringBinder.unbind(key, value.organisation + ":" + value.name + ":" + value.range)

    }

  implicit def localDateBindable(implicit strBinder: QueryStringBindable[String]): QueryStringBindable[LocalDate] =
    new QueryStringBindable[LocalDate] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, LocalDate]] =
        strBinder.bind(key, params)
          .map(_.flatMap(s => Try(LocalDate.parse(s)).toEither.left.map(_.getMessage)))

      override def unbind(key: String, value: LocalDate): String =
        strBinder.unbind(key, value.toString)
    }
}
