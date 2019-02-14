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

import play.api.libs.json.{Json, OFormat}
import scala.util.Try

case class SlugDependency(
  path       : String,
  version    : String,
  group      : String,
  artifact   : String,
  meta       : String = "")

case class SlugInfo(
  uri             : String,
  name            : String,
  version         : String,
  versionLong     : Long,
  teams           : List[String],
  runnerVersion   : String,
  classpath       : String,
  jdkVersion      : String,
  dependencies    : List[SlugDependency])

object SlugInfo {
  def toLong(s: String): Option[Long] =
    for {
      v   <- Version.parse(s)
      res <- Try {
               v.major.toLong * 1000 * 1000 +
               v.minor.toLong * 1000 +
               v.patch.toLong
             }.toOption
    } yield res
}

trait MongoSlugInfoFormats {
  implicit val sdFormat: OFormat[SlugDependency] =
    Json.format[SlugDependency]

  implicit val siFormat: OFormat[SlugInfo] = {
    implicit val vf = Version.mongoFormat
    Json.format[SlugInfo]
  }
}

object MongoSlugInfoFormats extends MongoSlugInfoFormats


trait ApiSlugInfoFormats {
  implicit val sdFormat: OFormat[SlugDependency] =
    Json.format[SlugDependency]

  implicit val siFormat: OFormat[SlugInfo] = {
    implicit val vf = Version.apiFormat
    Json.format[SlugInfo]
  }
}

object ApiSlugInfoFormats extends ApiSlugInfoFormats