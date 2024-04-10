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

case class MetaArtefact(
  name              : String,
  version           : Version,
  uri               : String,
  gitUrl            : Option[String]      = None,
  dependencyDotBuild: Option[String]      = None,
  buildInfo         : Map[String, String] = Map.empty,
  modules           : Seq[MetaArtefactModule],
  created           : Instant             = Instant.now(),
  latest            : Boolean             = false
) {
  def subModuleNames = modules.collect { case x if x.name != name => x.name }
}

object MetaArtefact {
  private implicit val mamf: Format[MetaArtefactModule] = MetaArtefactModule.format

  val mongoFormat: OFormat[MetaArtefact] =
    ( (__ \ "name"              ).format[String]
    ~ (__ \ "version"           ).format[Version](Version.format)
    ~ (__ \ "uri"               ).format[String]
    ~ (__ \ "gitUrl"            ).formatNullable[String]
    ~ (__ \ "dependencyDotBuild").formatNullable[String]
    ~ (__ \ "buildInfo"         ).format[Map[String, String]]
    ~ (__ \ "modules"           ).format[Seq[MetaArtefactModule]]
    ~ (__ \ "created"           ).format[Instant](MongoJavatimeFormats.instantFormat)
    ~ (__ \ "latest"            ).formatWithDefault[Boolean](false)
    )(MetaArtefact.apply, ma => (ma.name, ma.version, ma.uri, ma.gitUrl, ma.dependencyDotBuild, ma.buildInfo, ma.modules, ma.created, ma.latest))

  val apiFormat: OFormat[MetaArtefact] =
    ( (__ \ "name"              ).format[String]
    ~ (__ \ "version"           ).format[Version](Version.format)
    ~ (__ \ "uri"               ).format[String]
    ~ (__ \ "gitUrl"            ).formatNullable[String]
    ~ (__ \ "dependencyDotBuild").formatNullable[String]
    ~ (__ \ "buildInfo"         ).format[Map[String, String]]
    ~ (__ \ "modules"           ).format[Seq[MetaArtefactModule]]
    ~ (__ \ "created"           ).format[Instant]
    ~ (__ \ "latest"            ).formatWithDefault[Boolean](false)
    )(MetaArtefact.apply, ma => (ma.name, ma.version, ma.uri, ma.gitUrl, ma.dependencyDotBuild, ma.buildInfo, ma.modules, ma.created, ma.latest))
}

case class MetaArtefactModule(
  name                 : String,
  group                : String,
  sbtVersion           : Option[Version],
  crossScalaVersions   : Option[List[Version]],
  publishSkip          : Option[Boolean],
  dependencyDotCompile : Option[String],
  dependencyDotProvided: Option[String],
  dependencyDotTest    : Option[String],
  dependencyDotIt      : Option[String]
)

object MetaArtefactModule {
  val format: OFormat[MetaArtefactModule] = {
    implicit val vf = Version.format
    ( (__ \ "name"                 ).format[String]
    ~ (__ \ "group"                ).formatWithDefault[String]("uk.gov.hmrc")
    ~ (__ \ "sbtVersion"           ).formatNullable[Version]
    ~ (__ \ "crossScalaVersions"   ).formatNullable[List[Version]]
    ~ (__ \ "publishSkip"          ).formatNullable[Boolean]
    ~ (__ \ "dependencyDotCompile" ).formatNullable[String]
    ~ (__ \ "dependencyDotProvided").formatNullable[String]
    ~ (__ \ "dependencyDotTest"    ).formatNullable[String]
    ~ (__ \ "dependencyDotIt"      ).formatNullable[String]
    )(MetaArtefactModule.apply, ma => (ma.name, ma.group, ma.sbtVersion, ma.crossScalaVersions, ma.publishSkip, ma.dependencyDotCompile, ma.dependencyDotProvided, ma.dependencyDotTest, ma.dependencyDotIt))
  }
}
