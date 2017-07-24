/*
 * Copyright 2017 HM Revenue & Customs
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

import java.util.Date

import play.api.libs.json.Json

case class MongoLibraryVersion(libraryName: String, version: Version, updateDate: Long = new Date().getTime)
object MongoLibraryVersion {
  implicit val format = Json.format[MongoLibraryVersion]
}


case class LibraryVersion(libraryName: String, version: Version)
object LibraryVersion {
  implicit val format = Json.format[LibraryVersion]

  def apply(libraryVersion: MongoLibraryVersion): LibraryVersion = LibraryVersion(libraryVersion.libraryName, libraryVersion.version)
}