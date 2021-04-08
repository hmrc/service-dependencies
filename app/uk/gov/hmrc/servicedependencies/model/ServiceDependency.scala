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

import play.api.libs.json.{__, JsError, JsResult, JsString, JsSuccess, Format, OFormat}
import play.api.libs.functional.syntax._


sealed trait DependencyScope { def asString: String }
object DependencyScope {
  case object Compile extends DependencyScope { val asString = "compile" }
  case object Test    extends DependencyScope { val asString = "test"    }
  case object Build   extends DependencyScope { val asString = "build"   }

  val values: List[DependencyScope] =
    List(Compile, Test, Build)

  def parse(s: String): Either[String, DependencyScope] =
    values
      .find(_.asString == s)
      .toRight(s"Invalid dependency scope - should be one of: ${values.map(_.asString).mkString(", ")}")
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
  buildFlag       : Boolean
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
    )(ServiceDependencyWrite.apply _, unlift(ServiceDependencyWrite.unapply _))
}

case class ServiceDependency(
  slugName    : String,
  slugVersion : String,
  teams       : List[String], // not stored in db
  depGroup    : String,
  depArtefact : String,
  depVersion  : String,
  scalaVersion: Option[String],
  scopes      : Set[DependencyScope]
) {
  lazy val depSemanticVersion: Option[Version] =
    Version.parse(depVersion)
}

trait ApiServiceDependencyFormats {
  val derivedMongoFormat: OFormat[ServiceDependency] =
    ( (__ \ "slugName"    ).format[String]
    ~ (__ \ "slugVersion" ).format[String]
    ~ (__ \ "group"       ).format[String]
    ~ (__ \ "artefact"    ).format[String]
    ~ (__ \ "version"     ).format[String]
    ~ (__ \ "scalaVersion").formatNullable[String]
    ~ (__ \ "compile"     ).format[Boolean]
    ~ (__ \ "test"        ).format[Boolean]
    ~ (__ \ "build"       ).format[Boolean]
    )( (sn, sv, g, a, v, scv, c, t, b) =>
         ServiceDependency(
           slugName     = sn,
           slugVersion  = sv,
           teams        = List.empty,
           depGroup     = g,
           depArtefact  = a,
           depVersion   = v,
           scalaVersion = scv,
           scopes       =  Set[DependencyScope](DependencyScope.Compile).filter(_ => c) ++
                           Set[DependencyScope](DependencyScope.Test   ).filter(_ => t) ++
                           Set[DependencyScope](DependencyScope.Build  ).filter(_ => b)
       )
     , sd =>
         (sd.slugName,
          sd.slugVersion,
          sd.depGroup,
          sd.depArtefact,
          sd.depVersion,
          sd.scalaVersion,
          sd.scopes.contains(DependencyScope.Compile),
          sd.scopes.contains(DependencyScope.Test),
          sd.scopes.contains(DependencyScope.Build)
         )
     )

  private def toResult[A](e: Either[String, A]): JsResult[A] =
    e match {
      case Right(r) => JsSuccess(r)
      case Left(l)  => JsError(__, l)
    }

  lazy val dependencyScopeFormat: Format[DependencyScope] =
    Format(
      _.validate[String].flatMap(s => toResult(DependencyScope.parse(s))),
      f => JsString(f.asString)
    )

  val serviceDependencyFormat: OFormat[ServiceDependency] = {
    implicit val dsf = dependencyScopeFormat
    ( (__ \ "slugName"    ).format[String]
    ~ (__ \ "slugVersion" ).format[String]
    ~ (__ \ "teams"       ).format[List[String]]
    ~ (__ \ "depGroup"    ).format[String]
    ~ (__ \ "depArtefact" ).format[String]
    ~ (__ \ "depVersion"  ).format[String]
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
