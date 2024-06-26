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

import play.api.mvc.QueryStringBindable

case class BobbyRuleQuery(
  organisation: String,
  name        : String,
  range       : String
)

object BobbyRuleQuery:
  given bobbyRuleQueryStringBindable(using stringBinder: QueryStringBindable[String]): QueryStringBindable[BobbyRuleQuery] with

    private def stringToBobby(key: String, rule: String): Either[String, BobbyRuleQuery] =
      rule.split(":") match
        case Array(organisation, name, range) => Right(BobbyRuleQuery(organisation, name, range))
        case _                                => Left(s"Invalid $key")

    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, BobbyRuleQuery]] =
      stringBinder.bind(key, params)
        .map(_.flatMap(stringToBobby(key, _)))

    override def unbind(key: String, value: BobbyRuleQuery): String =
      stringBinder.unbind(key, value.organisation + ":" + value.name + ":" + value.range)
