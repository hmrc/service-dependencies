/*
 * Copyright 2022 HM Revenue & Customs
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

import scala.util.{Failure, Try}

object Binders {

  implicit def queryStringBindable(implicit listOfStringBinder: QueryStringBindable[List[String]]): QueryStringBindable[BobbyRuleQuery] =
    new QueryStringBindable[BobbyRuleQuery] {

      def stringToBobby(rule: String): Try[BobbyRuleQuery] = {
        val split = rule.split(":")
        Try(BobbyRuleQuery(split(0), split(1), split(2)))
      }

      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, BobbyRuleQuery]] = {
        params
          .get(key)
          .flatMap(_.headOption)
          .map(rule => stringToBobby(rule).toEither.left.map(_.getMessage))
      }

      override def unbind(key: String, value: BobbyRuleQuery): String =
        value.organisation + ":" + value.name + ":" + value.range
    }
}
