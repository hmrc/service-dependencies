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

import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.{LocalDate, Instant}

case class BobbyReport(
  repoName    : String
, repoVersion : Version
, repoType    : RepoType
, violations  : Seq[BobbyReport.Violation]
, lastUpdated : Instant
, latest      : Boolean
, production  : Boolean
, qa          : Boolean
, staging     : Boolean
, development : Boolean
, externalTest: Boolean
, integration : Boolean
)

object BobbyReport:
  val apiFormat   = format(using Violation.apiFormat  , summon[Format[Instant]])
  val mongoFormat = format(using Violation.mongoFormat, MongoJavatimeFormats.instantFormat)

  private def format(using Format[Violation], Format[Instant]): Format[BobbyReport] =
    ( (__ \ "repoName"    ).format[String]
    ~ (__ \ "repoVersion" ).format[Version](Version.format)
    ~ (__ \ "repoType"    ).format[RepoType]
    ~ (__ \ "violations"  ).format[Seq[Violation]]
    ~ (__ \ "lastUpdated" ).format[Instant]
    ~ (__ \ "latest"      ).formatWithDefault[Boolean](false)
    ~ (__ \ "production"  ).formatWithDefault[Boolean](false)
    ~ (__ \ "qa"          ).formatWithDefault[Boolean](false)
    ~ (__ \ "staging"     ).formatWithDefault[Boolean](false)
    ~ (__ \ "development" ).formatWithDefault[Boolean](false)
    ~ (__ \ "externaltest").formatWithDefault[Boolean](false)
    ~ (__ \ "integration" ).formatWithDefault[Boolean](false)
    )(BobbyReport.apply, d => Tuple.fromProductTyped(d))

  case class Violation(
    depGroup    : String
  , depArtefact : String
  , depVersion  : Version
  , depScopes   : Set[DependencyScope]
  , range       : BobbyVersionRange
  , reason      : String
  , from        : LocalDate
  , exempt      : Boolean
  )

  object Violation:
    val apiFormat   = format(using summon[Format[LocalDate]])
    val mongoFormat = format(using MongoJavatimeFormats.localDateFormat)

    private def format(using Format[LocalDate]): Format[Violation] =
      given Format[BobbyVersionRange] = BobbyVersionRange.format
      given Format[DependencyScope]   = DependencyScope.dependencyScopeFormat
      ( (__ \ "depGroup"   ).format[String]
      ~ (__ \ "depArtefact").format[String]
      ~ (__ \ "depVersion" ).format[Version]
      ~ (__ \ "depScopes"  ).format[Set[DependencyScope]]
      ~ (__ \ "range"      ).format[BobbyVersionRange]
      ~ (__ \ "reason"     ).format[String]
      ~ (__ \ "from"       ).format[LocalDate]
      ~ (__ \ "exempt"     ).format[Boolean]
      )(Violation.apply, br => Tuple.fromProductTyped(br))
