/*
 * Copyright 2022 HM Revenue & Customs
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

case class MetaArtefact(
  name              : String,
  version           : Version,
  uri               : String,
  gitUrl            : Option[String]      = None,
  dependencyDotBuild: Option[String]      = None,
  buildInfo         : Map[String, String] = Map.empty,
  modules           : Seq[MetaArtefactModule],
  created           : Instant             = Instant.now()
)

object MetaArtefact {
  private implicit val mamf = MetaArtefactModule.format

  val mongoFormat: OFormat[MetaArtefact] =
    ( (__ \ "name"              ).format[String]
    ~ (__ \ "version"           ).format[Version](Version.format)
    ~ (__ \ "uri"               ).format[String]
    ~ (__ \ "gitUrl"            ).formatNullable[String]
    ~ (__ \ "dependencyDotBuild").formatNullable[String]
    ~ (__ \ "buildInfo"         ).format[Map[String, String]]
    ~ (__ \ "modules"           ).format[Seq[MetaArtefactModule]]
    ~ (__ \ "created"           ).format[Instant](MongoJavatimeFormats.instantFormat)
    )(MetaArtefact.apply, unlift(MetaArtefact.unapply))

  val apiFormat: OFormat[MetaArtefact] =
    ( (__ \ "name"              ).format[String]
    ~ (__ \ "version"           ).format[Version](Version.format)
    ~ (__ \ "uri"               ).format[String]
    ~ (__ \ "gitUrl"            ).formatNullable[String]
    ~ (__ \ "dependencyDotBuild").formatNullable[String]
    ~ (__ \ "buildInfo"         ).format[Map[String, String]]
    ~ (__ \ "modules"           ).format[Seq[MetaArtefactModule]]
    ~ (__ \ "created"           ).format[Instant]
    )(MetaArtefact.apply, unlift(MetaArtefact.unapply))
}

case class MetaArtefactModule(
  name                : String,
  group               : String,
  dependencyDotCompile: Option[String],
  dependencyDotTest   : Option[String]
)

object MetaArtefactModule {
  val format: OFormat[MetaArtefactModule] =
    ( (__ \ "name"                ).format[String]
    ~ (__ \ "group"               ).formatWithDefault[String]("uk.gov.hmrc")
    ~ (__ \ "dependencyDotCompile").formatNullable[String]
    ~ (__ \ "dependencyDotTest"   ).formatNullable[String]
    )(MetaArtefactModule.apply, unlift(MetaArtefactModule.unapply))
}
