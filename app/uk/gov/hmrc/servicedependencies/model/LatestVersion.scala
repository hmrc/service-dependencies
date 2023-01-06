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

import java.time.Instant

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

case class LatestVersion(
    name        : String
  , group       : String
  , version     : Version
  , updateDate  : Instant = Instant.now()
  )

object LatestVersion {
  private def ignore[A] = OWrites[A](_ => Json.obj())

  val apiWrites: Writes[LatestVersion] = {
    implicit val vf = Version.format
    ( (__ \ "artefact").write[String]
    ~ (__ \ "group"   ).write[String]
    ~ (__ \ "version" ).write[Version]
    ~ ignore[Instant]
    )(unlift(LatestVersion.unapply))
  }

  val mongoFormat = {
    implicit val iF  = MongoJavatimeFormats.instantFormat
    implicit val vf  = Version.format
    ( (__ \ "name"        ).format[String]
    ~ (__ \ "group"       ).format[String]
    ~ (__ \ "version"     ).format[Version]
    ~ (__ \ "updateDate"  ).format[Instant]
    )(LatestVersion.apply, unlift(LatestVersion.unapply))
  }

  val schema =
    """
    { bsonType: "object"
    , required: [ "name", "group", "version", "updateDate" ]
    , properties:
      { name         : { bsonType: "string" }
      , group        : { bsonType: "string" }
      , version      : { bsonType: "string" }
      , updateDate   : { bsonType: "date" }
      }
    }
    """
}

case class DependencyVersion(
    name   : String
  , group  : String
  , version: Option[Version]
  )
