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
                             // Initially we didn't store this information - this is was the assumption at the time.
                             .inmap[String](_.getOrElse("uk.gov.hmrc"), Some.apply)
    ~ (__ \ "currentVersion").format[Version]
    )(MongoRepositoryDependency.apply, unlift(MongoRepositoryDependency.unapply))
  }
}

case class MongoRepositoryDependencies(
    repositoryName       : String
  , libraryDependencies  : Seq[MongoRepositoryDependency]
  , sbtPluginDependencies: Seq[MongoRepositoryDependency] = Nil
  , otherDependencies    : Seq[MongoRepositoryDependency]
  , updateDate           : Instant                        = Instant.now()
  )

object MongoRepositoryDependencies {

  implicit val format = {
    implicit val iF = MongoJavatimeFormats.instantFormats
    Json.format[MongoRepositoryDependencies]
  }
}
