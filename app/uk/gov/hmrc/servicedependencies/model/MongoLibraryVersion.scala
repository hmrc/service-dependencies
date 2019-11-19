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

import java.time.Instant

import play.api.libs.json.Json
import uk.gov.hmrc.mongo.play.json.MongoJavatimeFormats

case class MongoLibraryVersion(libraryName: String, version: Option[Version], updateDate: Instant = Instant.now())
object MongoLibraryVersion {
  implicit val instantF    = MongoJavatimeFormats.instantFormats
  implicit val format = {
    implicit val vf = Version.mongoFormat
    Json.format[MongoLibraryVersion]
  }
}

case class LibraryVersion(libraryName: String, version: Option[Version])
object LibraryVersion {
  implicit val format = {
    implicit val vf = Version.apiFormat
    Json.format[LibraryVersion]
  }

  def apply(mongoLibraryVersion: MongoLibraryVersion): LibraryVersion =
    LibraryVersion(mongoLibraryVersion.libraryName, mongoLibraryVersion.version)
}
