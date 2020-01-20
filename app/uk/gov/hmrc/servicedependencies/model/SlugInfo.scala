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

import java.time.LocalDateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed trait SlugInfoFlag { def asString: String }
object SlugInfoFlag {
  case object Latest          extends SlugInfoFlag { val asString = "latest"         }
  case object Production      extends SlugInfoFlag { val asString = "production"     }
  case object ExternalTest    extends SlugInfoFlag { val asString = "external test"  }
  case object Staging         extends SlugInfoFlag { val asString = "staging"        }
  case object QA              extends SlugInfoFlag { val asString = "qa"             }
  case object Integration     extends SlugInfoFlag { val asString = "integration"    }
  case object Development     extends SlugInfoFlag { val asString = "development"    }

  val values: List[SlugInfoFlag] = List(Latest, Production, ExternalTest, Staging, QA, Integration, Development)

  def parse(s: String): Option[SlugInfoFlag] =
    values.find(_.asString.equalsIgnoreCase(s))
}

case class SlugDependency(
  path       : String,
  version    : String,
  group      : String,
  artifact   : String,
  meta       : String = "")

case class JavaInfo(
  version : String,
  vendor  : String,
  kind    : String)

case class SlugInfo(
  uri              : String,
  created          : LocalDateTime,
  name             : String,
  version          : Version,
  teams            : List[String],
  runnerVersion    : String,
  classpath        : String,
  java             : JavaInfo,
  dependencies     : List[SlugDependency],
  applicationConfig: String,
  slugConfig       : String,
  latest           : Boolean,
  production       : Boolean,
  qa               : Boolean,
  staging          : Boolean,
  development      : Boolean,
  externalTest     : Boolean,
  integration      : Boolean
  ) {
    lazy val classpathOrderedDependencies: List[SlugDependency] =
      classpath.split(":")
        .map(_.replace("$lib_dir/", s"./$name-$version/lib/"))
        .toList
        .flatMap(path => dependencies.filter(_.path == path))
  }

case class DependencyConfig(
    group   : String
  , artefact: String
  , version : String
  , configs : Map[String, String]
  )

trait MongoSlugInfoFormats {
  implicit val sdFormat: OFormat[SlugDependency] =
    Json.format[SlugDependency]

  implicit val javaFormat: OFormat[JavaInfo] =
    Json.format[JavaInfo]

  val ignore = OWrites[Any](_ => Json.obj())

  implicit val slugInfoFormat: OFormat[SlugInfo] =
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "created"          ).format[LocalDateTime]
    ~ (__ \ "name"             ).format[String]
    ~ (__ \ "version"          ).format[String].inmap[Version](Version.apply, _.original)
    ~ OFormat( Reads.pure(List.empty[String])
             , ignore
             )
    ~ (__ \ "runnerVersion"    ).format[String]
    ~ (__ \ "classpath"        ).format[String]
    ~ (__ \ "java"             ).format[JavaInfo]
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "applicationConfig").formatNullable[String].inmap[String](_.getOrElse(""), Option.apply)
    ~ (__ \ "slugConfig"       ).formatNullable[String].inmap[String](_.getOrElse(""), Option.apply)
    ~ (__ \ "latest"           ).format[Boolean]
    ~ (__ \ "production"       ).format[Boolean]
    ~ (__ \ "qa"               ).format[Boolean]
    ~ (__ \ "staging"          ).format[Boolean]
    ~ (__ \ "development"      ).format[Boolean]
    ~ (__ \ "external test"    ).format[Boolean]
    ~ (__ \ "integration"      ).format[Boolean]
    )(SlugInfo.apply, unlift(SlugInfo.unapply))

  implicit val serviceDependencyFormat: OFormat[ServiceDependency] =
    ( (__ \ "slugName"              ).format[String]
      ~ (__ \ "slugVersion"          ).format[String]
      ~ (__ \ "teams"             ).formatWithDefault[List[String]](List.empty)
      ~ (__ \ "depGroup"          ).format[String]
      ~ (__ \ "depArtifact"          ).format[String]
      ~ (__ \ "depVersion"          ).format[String]
      )(ServiceDependency.apply, unlift(ServiceDependency.unapply))

  implicit val jdkVersionFormat: OFormat[JDKVersion] = {
    ( (__ \ "name"              ).format[String]
      ~ (__ \ "version"          ).format[String]
      ~ (__ \ "vendor"           ).formatWithDefault[String]("Oracle")
      ~ (__ \ "kind"             ).formatWithDefault[String]("JDK")
      )(JDKVersion.apply, unlift(JDKVersion.unapply))
  }

  implicit val groupArtefactsFormat: OFormat[GroupArtefacts] = {
    ( (__ \ "_id"              ).format[String]
      ~ (__ \ "artifacts"          ).format[List[String]]
      )(GroupArtefacts.apply, unlift(GroupArtefacts.unapply))
  }

  val dcFormat: OFormat[DependencyConfig] =
    ( (__ \ "group"   ).format[String]
    ~ (__ \ "artefact").format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "configs" ).format[Map[String, String]]
                       .inmap[Map[String, String]]( _.map { case (k, v) => (k.replaceAll("_DOT_", "."    ), v) }  // for mongo < 3.6 compatibility - '.' and '$'' not permitted in keys
                                                  , _.map { case (k, v) => (k.replaceAll("\\."  , "_DOT_"), v) }
                                                  )
    )(DependencyConfig.apply, unlift(DependencyConfig.unapply))

}

object MongoSlugInfoFormats extends MongoSlugInfoFormats

trait ApiSlugInfoFormats {
  implicit val sdFormat: OFormat[SlugDependency] =
    Json.format[SlugDependency]

  implicit val javaFormat: OFormat[JavaInfo] =
    Json.format[JavaInfo]

  implicit val siFormat: OFormat[SlugInfo] = {
    implicit val vf = Version.apiFormat
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "created"          ).format[LocalDateTime]
    ~ (__ \ "name"             ).format[String]
    ~ (__ \ "version"          ).format[String].inmap[Version](Version.apply, _.original)
    ~ (__ \ "teams"            ).format[List[String]]
    ~ (__ \ "runnerVersion"    ).format[String]
    ~ (__ \ "classpath"        ).format[String]
    ~ (__ \ "java"             ).format[JavaInfo]
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "applicationConfig").format[String]
    ~ (__ \ "slugConfig"       ).format[String]
    ~ (__ \ "latest"           ).format[Boolean]
    ~ (__ \ "production"       ).format[Boolean]
    ~ (__ \ "qa"               ).format[Boolean]
    ~ (__ \ "staging"          ).format[Boolean]
    ~ (__ \ "development"      ).format[Boolean]
    ~ (__ \ "external test"    ).format[Boolean]
    ~ (__ \ "integration"      ).format[Boolean]
    )(SlugInfo.apply, unlift(SlugInfo.unapply))
  }

  val dcFormat: OFormat[DependencyConfig] =
    ( (__ \ "group"   ).format[String]
    ~ (__ \ "artefact").format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "configs" ).format[Map[String, String]]
    )(DependencyConfig.apply, unlift(DependencyConfig.unapply))

  val slugReads: Reads[SlugInfo] =
    ( (__ \ "uri").read[String]
    ~ (__ \ "created").read[LocalDateTime]
    ~ (__ \ "name").read[String]
    ~ (__ \ "version").read[String].map(Version.apply)
    ~ (__ \ "teams").read[List[String]]
    ~ (__ \ "runnerVersion").read[String]
    ~ (__ \ "classpath").read[String]
    ~ (__ \ "java").read[JavaInfo]
    ~ (__ \ "dependencies").read[List[SlugDependency]]
    ~ (__ \ "applicationConfig").read[String]
    ~ (__ \ "slugConfig").read[String]
    ~ (__ \ "latest").read[Boolean]
    ~ Reads.pure(false)
    ~ Reads.pure(false)
    ~ Reads.pure(false)
    ~ Reads.pure(false)
    ~ Reads.pure(false)
    ~ Reads.pure(false)
    )(SlugInfo.apply _)
}

object ApiSlugInfoFormats extends ApiSlugInfoFormats