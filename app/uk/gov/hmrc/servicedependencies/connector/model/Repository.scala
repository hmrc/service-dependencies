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

package uk.gov.hmrc.servicedependencies.connector.model

import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, Writes, __}
import uk.gov.hmrc.servicedependencies.model.RepoType

case class Repository(
  name      : String,
  teamNames : Seq[String],
  repoType  : RepoType,
  isArchived: Boolean
)

object Repository:

  given Format[Repository] =
    ( ( __ \ "name"     ).format[String]
    ~ (__ \ "teamNames" ).format[Seq[String]]
    ~ (__ \ "repoType"  ).format[RepoType]
    ~ (__ \ "isArchived").format[Boolean]
    )(apply, r => Tuple.fromProductTyped(r))
