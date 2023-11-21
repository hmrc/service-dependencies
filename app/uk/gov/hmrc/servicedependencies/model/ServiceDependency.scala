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

import play.api.libs.json.{__, JsError, JsString, JsSuccess, Format, OFormat}
import play.api.libs.functional.syntax._

sealed trait DependencyScope { def asString: String }

object DependencyScope {
  case object Compile  extends DependencyScope { override val asString = "compile"  }
  case object Provided extends DependencyScope { override val asString = "provided" }
  case object Test     extends DependencyScope { override val asString = "test"     }
  case object It       extends DependencyScope { override val asString = "it"       }
  case object Build    extends DependencyScope { override val asString = "build"    }

  val values: List[DependencyScope] =
    List(Compile, Provided, Test, It, Build)

  def parse(s: String): Either[String, DependencyScope] =
    values
      .find(_.asString == s)
      .toRight(s"Invalid dependency scope - should be one of: ${values.map(_.asString).mkString(", ")}")

  val dependencyScopeFormat: Format[DependencyScope] = Format(
       _.validate[String].flatMap(DependencyScope.parse(_).fold(err => JsError(__, err), ds => JsSuccess(ds)))
    ,  f => JsString(f.asString)
  )

}


case class ServiceDependencyWrite(
  slugName    : String,
  slugVersion : Version,
  depGroup    : String,
  depArtefact : String,
  depVersion  : Version,
  scalaVersion: Option[String],
  // scope flag
  compileFlag : Boolean,
  providedFlag: Boolean,
  testFlag    : Boolean,
  itFlag      : Boolean,
  buildFlag   : Boolean,
)

object ServiceDependencyWrite {
  val format: OFormat[ServiceDependencyWrite] = {
    implicit val vf = Version.format
    ( (__ \ "slugName"      ).format[String]
    ~ (__ \ "slugVersion"   ).format[Version]
    ~ (__ \ "group"         ).format[String]
    ~ (__ \ "artefact"      ).format[String]
    ~ (__ \ "version"       ).format[Version]
    ~ (__ \ "scalaVersion"  ).formatNullable[String]
    ~ (__ \ "scope_compile" ).format[Boolean]
    ~ (__ \ "scope_provided").format[Boolean]
    ~ (__ \ "scope_test"    ).format[Boolean]
    ~ (__ \ "scope_it"      ).format[Boolean]
    ~ (__ \ "scope_build"   ).format[Boolean]
    )(ServiceDependencyWrite.apply _, unlift(ServiceDependencyWrite.unapply _))
  }
}

case class ServiceDependency(
  slugName    : String,
  slugVersion : Version,
  teams       : List[String], // not stored in db
  depGroup    : String,
  depArtefact : String,
  depVersion  : Version,
  scalaVersion: Option[String],
  scopes      : Set[DependencyScope]
)

trait ApiServiceDependencyFormats {
  implicit val vf: Format[Version] = Version.format
  val derivedMongoFormat: OFormat[ServiceDependency] =
    ( (__ \ "slugName"      ).format[String]
    ~ (__ \ "slugVersion"   ).format[Version]
    ~ (__ \ "group"         ).format[String]
    ~ (__ \ "artefact"      ).format[String]
    ~ (__ \ "version"       ).format[Version]
    ~ (__ \ "scalaVersion"  ).formatNullable[String]
    ~ (__ \ "scope_compile" ).format[Boolean]
    ~ (__ \ "scope_provided").format[Boolean]
    ~ (__ \ "scope_test"    ).format[Boolean]
    ~ (__ \ "scope_it"      ).format[Boolean]
    ~ (__ \ "scope_build"   ).format[Boolean]
    )( (sn, sv, g, a, v, scv, c, p, t, i, b) =>
         ServiceDependency(
           slugName     = sn,
           slugVersion  = sv,
           teams        = List.empty,
           depGroup     = g,
           depArtefact  = a,
           depVersion   = v,
           scalaVersion = scv,
           scopes       =  Set[DependencyScope](DependencyScope.Compile ).filter(_ => c) ++
                           Set[DependencyScope](DependencyScope.Provided).filter(_ => p) ++
                           Set[DependencyScope](DependencyScope.Test    ).filter(_ => t) ++
                           Set[DependencyScope](DependencyScope.It      ).filter(_ => i) ++
                           Set[DependencyScope](DependencyScope.Build   ).filter(_ => b)
       )
     , sd =>
         (sd.slugName,
          sd.slugVersion,
          sd.depGroup,
          sd.depArtefact,
          sd.depVersion,
          sd.scalaVersion,
          sd.scopes.contains(DependencyScope.Compile),
          sd.scopes.contains(DependencyScope.Provided),
          sd.scopes.contains(DependencyScope.Test),
          sd.scopes.contains(DependencyScope.It),
          sd.scopes.contains(DependencyScope.Build)
         )
     )

  val serviceDependencyFormat: OFormat[ServiceDependency] = {
    implicit val vf  = Version.format
    implicit val dsf = DependencyScope.dependencyScopeFormat
    ( (__ \ "slugName"    ).format[String]
    ~ (__ \ "slugVersion" ).format[Version]
    ~ (__ \ "teams"       ).format[List[String]]
    ~ (__ \ "depGroup"    ).format[String]
    ~ (__ \ "depArtefact" ).format[String]
    ~ (__ \ "depVersion"  ).format[Version]
    ~ (__ \ "scalaVersion").formatNullable[String]
    ~ (__ \ "scopes"      ).format[List[DependencyScope]]
    )((sn, sv, tm, g, a, v, scv, scopes) =>
      ServiceDependency(
           slugName     = sn,
           slugVersion  = sv,
           teams        = tm,
           depGroup     = g,
           depArtefact  = a,
           depVersion   = v,
           scalaVersion = scv,
           scopes       = scopes.toSet
       )
    , sd =>
         (sd.slugName,
          sd.slugVersion,
          sd.teams,
          sd.depGroup,
          sd.depArtefact,
          sd.depVersion,
          sd.scalaVersion,
          sd.scopes.toList
         )
    )
  }
}

object ApiServiceDependencyFormats extends ApiServiceDependencyFormats
