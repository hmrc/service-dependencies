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

import play.api.libs.json.{__, Json, OFormat, Reads, OWrites}
import play.api.libs.functional.syntax._

import scala.util.Try

sealed trait SlugInfoFlag { def s: String }
object SlugInfoFlag {
  case object Latest     extends SlugInfoFlag { val s = "latest"     }
  case object Production extends SlugInfoFlag { val s = "production" }
  case object QA         extends SlugInfoFlag { val s = "qa"         }

  val values = List(Latest, Production, QA)

  def parse(s: String): Option[SlugInfoFlag] =
    values.find(_.s == s)
}

case class SlugDependency(
  path       : String,
  version    : String,
  group      : String,
  artifact   : String,
  meta       : String = "")

case class SlugInfo(
  uri              : String,
  name             : String,
  version          : Version,
  teams            : List[String],
  runnerVersion    : String,
  classpath        : String,
  jdkVersion       : String,
  dependencies     : List[SlugDependency],
  referenceConfig  : String,
  applicationConfig: String,
  finalConfig      : String,
  latest           : Boolean
  )

trait MongoSlugInfoFormats {
  implicit val sdFormat: OFormat[SlugDependency] =
    Json.format[SlugDependency]

  val ignore = OWrites[Any](_ => Json.obj())

  implicit val siFormat: OFormat[SlugInfo] =
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "name"             ).format[String]
    ~ (__ \ "version"          ).format[String].inmap[Version](Version.apply, _.original)
    ~ OFormat( Reads.pure(List.empty[String])
             , ignore
             )
    ~ (__ \ "runnerVersion")    .format[String]
    ~ (__ \ "classpath"        ).format[String]
    ~ (__ \ "jdkVersion"       ).format[String]
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "referenceConfig"  ).format[String]
    ~ (__ \ "applicationConfig").format[String]
    ~ (__ \ "finalConfig"      ).format[String]
    ~ (__ \ "latest"           ).format[Boolean]
    )(SlugInfo.apply, unlift(SlugInfo.unapply))
}

object MongoSlugInfoFormats extends MongoSlugInfoFormats


trait ApiSlugInfoFormats {
  implicit val sdFormat: OFormat[SlugDependency] =
    Json.format[SlugDependency]

  // TODO remove config from slug info?
  implicit val siFormat: OFormat[SlugInfo] = {
    implicit val vf = Version.apiFormat
    Json.format[SlugInfo]
  }
}

object ApiSlugInfoFormats extends ApiSlugInfoFormats