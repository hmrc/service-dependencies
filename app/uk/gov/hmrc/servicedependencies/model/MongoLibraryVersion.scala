/*
 * Copyright 2020 HM Revenue & Customs
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

import play.api.libs.json.{Format, Json, __}
import play.api.libs.functional.syntax._
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

case class MongoLibraryVersion(
    name       : String
  , group      : String
  , version    : Option[Version]
  , updateDate : Instant = Instant.now()
  )

object MongoLibraryVersion {
  implicit val format = {
    implicit val iF = MongoJavatimeFormats.instantFormats
    implicit val vf = Version.mongoFormat
    ( (__ \ "libraryName").format[String]
    ~ (__ \ "group"      ).format[String]
    ~ (__ \ "version"    ).formatNullable[Version]
    ~ (__ \ "updateDate" ).format[Instant]
    )(MongoLibraryVersion.apply, unlift(MongoLibraryVersion.unapply))
  }

  val schema =
    """
    { bsonType: "object"
    , required: [ "libraryName", "group", "updateDate" ]
    , properties:
      { libraryName: { bsonType: "string" }
      , group      : { bsonType: "string" }
      , version    : { bsonType: "string"
                     , pattern: "^((\\d+)\\.(\\d+)\\.(\\d+)(.*)|(\\d+)\\.(\\d+)(.*)|(\\d+)(.*))$"
                     }
      , updateDate : { bsonType: "date" }
      }
    }
    """
}

case class LibraryVersion(
    name   : String
  , group  : String
  , version: Option[Version]
  )

object LibraryVersion {
  implicit val format = {
    implicit val vf = Version.apiFormat
    Json.format[LibraryVersion]
    ( (__ \ "libraryName").format[String]
    ~ (__ \ "group"      ).format[String]
    ~ (__ \ "version"    ).formatNullable[Version]
    )(LibraryVersion.apply, unlift(LibraryVersion.unapply))
  }
}
