/*
 * Copyright 2021 HM Revenue & Customs
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

case class MongoRepositoryDependency(
    name          : String
  , group         : String
  , currentVersion: Version
  )

object MongoRepositoryDependency {
  implicit val format: Format[MongoRepositoryDependency] = {
    implicit val vf = Version.mongoFormat
    ( (__ \ "name"          ).format[String]
    ~ (__ \ "group"         ).formatNullable[String]
    ~ (__ \ "currentVersion").format[Version]
    )(toMongoRepositoryDependency, fromMongoRepositoryDependency)
  }

  def toMongoRepositoryDependency(name: String, group: Option[String], currentVersion: Version): MongoRepositoryDependency = {
    // Initially we didn't store this information - this was the assumption at the time.
    val inferredGroup = name match {
      case "sbt-plugin"    => "com.typesafe.play"
      case "reactivemongo" => "org.reactivemongo"
      case "sbt"           => "org.scala-sbt"
      case _               => "uk.gov.hmrc"
    }
    MongoRepositoryDependency(name, group.getOrElse(inferredGroup), currentVersion)
  }

  def fromMongoRepositoryDependency(d: MongoRepositoryDependency): (String, Option[String], Version) =
    (d.name, Some(d.group), d.currentVersion)

}

case class MongoRepositoryDependencies(
    repositoryName       : String
  , libraryDependencies  : Seq[MongoRepositoryDependency]
  , sbtPluginDependencies: Seq[MongoRepositoryDependency]
  , otherDependencies    : Seq[MongoRepositoryDependency]
  , updateDate           : Instant                        = Instant.now()
  ) {
    lazy val dependencies =
      libraryDependencies ++ sbtPluginDependencies ++ otherDependencies
  }

object MongoRepositoryDependencies {

  implicit val format = {
    implicit val iF = MongoJavatimeFormats.instantFormat
    Json.format[MongoRepositoryDependencies]
    ( (__ \ "repositoryName"       ).format[String]
    ~ (__ \ "libraryDependencies"  ).format[Seq[MongoRepositoryDependency]]
    ~ (__ \ "sbtPluginDependencies").format[Seq[MongoRepositoryDependency]]
    ~ (__ \ "otherDependencies"    ).format[Seq[MongoRepositoryDependency]]
    ~ (__ \ "updateDate"           ).format[Instant]
    )(MongoRepositoryDependencies.apply, unlift(MongoRepositoryDependencies.unapply))
  }

    private val mongoRepositoryDependencySchema =
      """
      { bsonType: "object"
      , required: [ "name", "currentVersion" ]
      , properties:
        { libraryName   : { bsonType: "string" }
        , group         : { bsonType: "string" }
        , currentVersion: { bsonType: "string" }
        }
      }
      """

    val schema =
      s"""
      { bsonType: "object"
      , required: [ "repositoryName"
                  , "libraryDependencies"
                  , "sbtPluginDependencies"
                  , "otherDependencies"
                  , "updateDate"
                  ]
      , properties:
        { repositoryName       : { bsonType: "string" }
        , libraryDependencies  : { bsonType: "array", items: $mongoRepositoryDependencySchema }
        , sbtPluginDependencies: { bsonType: "array", items: $mongoRepositoryDependencySchema }
        , otherDependencies    : { bsonType: "array", items: $mongoRepositoryDependencySchema }
        , updateDate           : { bsonType: "date"  }
        }
      }
      """

}
