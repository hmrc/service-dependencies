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

import play.api.libs.json.{__, OFormat}
import play.api.libs.functional.syntax._


sealed trait DependencyScopeFlag { def asString: String }
object DependencyScopeFlag {
  case object Compile extends DependencyScopeFlag { val asString = "compile" }
  case object Test    extends DependencyScopeFlag { val asString = "test"    }
  case object Build   extends DependencyScopeFlag { val asString = "build"   }

  val values: List[DependencyScopeFlag] =
    List(Compile, Test, Build)

  def parse(s: String): Option[DependencyScopeFlag] =
    values.find(_.asString == s)
}


case class ServiceDependencyWrite(
  slugName        : String,
  slugVersion     : String,
  depGroup        : String,
  depArtefact     : String,
  depVersion      : String,
  scalaVersion    : Option[String],
  // scope flag
  compileFlag     : Boolean,
  testFlag        : Boolean,
  buildFlag       : Boolean,
  // env flags
  productionFlag  : Boolean,
  qaFlag          : Boolean,
  stagingFlag     : Boolean,
  developmentFlag : Boolean,
  externalTestFlag: Boolean,
  integrationFlag : Boolean,
  latestFlag      : Boolean
)

object ServiceDependencyWrite {
  val format: OFormat[ServiceDependencyWrite] =
    ( (__ \ "slugName"     ).format[String]
    ~ (__ \ "slugVersion"  ).format[String]
    ~ (__ \ "group"        ).format[String]
    ~ (__ \ "artefact"     ).format[String]
    ~ (__ \ "version"      ).format[String]
    ~ (__ \ "scalaVersion" ).formatNullable[String]
    ~ (__ \ "compile"      ).format[Boolean]
    ~ (__ \ "test"         ).format[Boolean]
    ~ (__ \ "build"        ).format[Boolean]
    ~ (__ \ "production"   ).format[Boolean]
    ~ (__ \ "qa"           ).format[Boolean]
    ~ (__ \ "staging"      ).format[Boolean]
    ~ (__ \ "development"  ).format[Boolean]
    ~ (__ \ "external test").format[Boolean]
    ~ (__ \ "integration"  ).format[Boolean]
    ~ (__ \ "latest"       ).format[Boolean]
    )(ServiceDependencyWrite.apply _, unlift(ServiceDependencyWrite.unapply _))
}

case class ServiceDependency(
  slugName    : String,
  slugVersion : String,
  teams       : List[String], // not stored in db
  depGroup    : String,
  depArtefact : String,
  depVersion  : String
) {
  lazy val depSemanticVersion: Option[Version] =
    Version.parse(depVersion)
}

trait ApiServiceDependencyFormats {

  val derivedMongoFormat: OFormat[ServiceDependency] =
    ( (__ \ "slugName"   ).format[String]
    ~ (__ \ "slugVersion").format[String]
    ~ (__ \ "teams"      ).formatWithDefault[List[String]](List.empty[String])
    ~ (__ \ "group"      ).format[String]
    ~ (__ \ "artefact"   ).format[String]
    ~ (__ \ "version"    ).format[String]
    )(ServiceDependency.apply, unlift(ServiceDependency.unapply))

  val sdFormat: OFormat[ServiceDependency] =
    ( (__ \ "slugName"   ).format[String]
    ~ (__ \ "slugVersion").format[String]
    ~ (__ \ "teams"      ).format[List[String]]
    ~ (__ \ "depGroup"   ).format[String]
    ~ (__ \ "depArtefact").format[String]
    ~ (__ \ "depVersion" ).format[String]
    )(ServiceDependency.apply, unlift(ServiceDependency.unapply))
}

object ApiServiceDependencyFormats extends ApiServiceDependencyFormats
