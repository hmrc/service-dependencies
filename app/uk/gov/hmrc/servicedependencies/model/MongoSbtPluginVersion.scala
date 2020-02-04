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

case class MongoSbtPluginVersion(
    name         : String
  , group        : String
  , version      : Option[Version]
  , updateDate   : Instant = Instant.now()
  )

object MongoSbtPluginVersion {
  implicit val format = {
    implicit val iF = MongoJavatimeFormats.instantFormats
    implicit val vf = Version.mongoFormat
    ( (__ \ "sbtPluginName").format[String]
    ~ (__ \ "group"        ).format[String]
    ~ (__ \ "version"      ).formatNullable[Version]
    ~ (__ \ "updateDate"   ).format[Instant]
    )(MongoSbtPluginVersion.apply, unlift(MongoSbtPluginVersion.unapply))
  }

  val schema =
    """
    { bsonType: "object"
    , required: [ "sbtPluginName", "group", "updateDate" ]
    , properties:
      { sbtPluginName: { bsonType: "string" }
      , group        : { bsonType: "string" }
      , version      : { bsonType: "string"
                       , pattern: "^((\\d+)\\.(\\d+)\\.(\\d+)(.*)|(\\d+)\\.(\\d+)(.*)|(\\d+)(.*))$"
                       }
      , updateDate   : { bsonType: "date" }
      }
    }
    """
}

case class SbtPluginVersion(
    name   : String
  , group  : String
  , version: Option[Version]
  )

object SbtPluginVersion {
  implicit val format: Format[SbtPluginVersion] = {
    implicit val vd = Version.apiFormat
    ( (__ \ "sbtPluginName").format[String]
    ~ (__ \ "group"        ).format[String]
    ~ (__ \ "version"      ).formatNullable[Version]
    )(SbtPluginVersion.apply, unlift(SbtPluginVersion.unapply))
  }
}
