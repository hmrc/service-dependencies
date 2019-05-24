/*
 * Copyright 2019 HM Revenue & Customs
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

import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed trait SlugInfoFlag { def s: String }
object SlugInfoFlag {
  case object Latest          extends SlugInfoFlag { val s = "latest"         }
  case object Production      extends SlugInfoFlag { val s = "production"     }
  case object QA              extends SlugInfoFlag { val s = "qa"             }
  case object Staging         extends SlugInfoFlag { val s = "staging"        }
  case object Development     extends SlugInfoFlag { val s = "development"    }
  case object ExternalTest    extends SlugInfoFlag { val s = "external test"  }

  val values: List[SlugInfoFlag] = List(Latest, Production, QA, Staging, Development, ExternalTest)

  def parse(s: String): Option[SlugInfoFlag] =
    values.find(_.s.equalsIgnoreCase(s))
}

case class SlugDependency(
  path       : String,
  version    : String,
  group      : String,
  artifact   : String,
  meta       : String = "")

case class SlugInfo(
  uri              : String,
  name             : String,
  version          : Version,
  teams            : List[String],
  runnerVersion    : String,
  classpath        : String,
  jdkVersion       : String,
  dependencies     : List[SlugDependency],
  applicationConfig: String,
  slugConfig       : String,
  latest           : Boolean,
  production       : Boolean,
  qa               : Boolean,
  staging          : Boolean,
  development      : Boolean,
  externalTest     : Boolean
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

  val ignore = OWrites[Any](_ => Json.obj())

  implicit val siFormat: OFormat[SlugInfo] =
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "name"             ).format[String]
    ~ (__ \ "version"          ).format[String].inmap[Version](Version.apply, _.original)
    ~ OFormat( Reads.pure(List.empty[String])
             , ignore
             )
    ~ (__ \ "runnerVersion"    ).format[String]
    ~ (__ \ "classpath"        ).format[String]
    ~ (__ \ "jdkVersion"       ).format[String]
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "applicationConfig").formatNullable[String].inmap[String](_.getOrElse(""), Option.apply)
    ~ (__ \ "slugConfig"       ).formatNullable[String].inmap[String](_.getOrElse(""), Option.apply)
    ~ (__ \ "latest"           ).format[Boolean]
    ~ (__ \ "production"       ).format[Boolean]
    ~ (__ \ "qa"               ).format[Boolean]
    ~ (__ \ "staging"          ).format[Boolean]
    ~ (__ \ "development"      ).format[Boolean]
    ~ (__ \ "external test"    ).format[Boolean]
    )(SlugInfo.apply, unlift(SlugInfo.unapply))


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

  implicit val siFormat: OFormat[SlugInfo] = {
    implicit val vf = Version.apiFormat
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "name"             ).format[String]
    ~ (__ \ "version"          ).format[String].inmap[Version](Version.apply, _.original)
    ~ (__ \ "teams"            ).format[List[String]]
    ~ (__ \ "runnerVersion"    ).format[String]
    ~ (__ \ "classpath"        ).format[String]
    ~ (__ \ "jdkVersion"       ).format[String]
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "applicationConfig").format[String]
    ~ (__ \ "slugConfig"       ).format[String]
    ~ (__ \ "latest"           ).format[Boolean]
    ~ (__ \ "production"       ).format[Boolean]
    ~ (__ \ "qa"               ).format[Boolean]
    ~ (__ \ "staging"          ).format[Boolean]
    ~ (__ \ "development"      ).format[Boolean]
    ~ (__ \ "external test"    ).format[Boolean]
    )(SlugInfo.apply, unlift(SlugInfo.unapply))
  }

  val dcFormat: OFormat[DependencyConfig] =
    ( (__ \ "group"   ).format[String]
    ~ (__ \ "artefact").format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "configs" ).format[Map[String, String]]
    )(DependencyConfig.apply, unlift(DependencyConfig.unapply))

  val slugReads: Reads[SlugInfo] = (
    (__ \ "uri").read[String] and
      (__ \ "name").read[String] and
      (__ \ "version").read[String].map(Version.apply) and
      (__ \ "teams").read[List[String]] and
      (__ \ "runnerVersion").read[String] and
      (__ \ "classpath").read[String] and
      (__ \ "jdkVersion").read[String] and
      (__ \ "dependencies").read[List[SlugDependency]] and
      (__ \ "applicationConfig").read[String] and
      (__ \ "slugConfig").read[String] and
      (__ \ "latest").read[Boolean] and
      Reads.pure(false) and
      Reads.pure(false) and
      Reads.pure(false) and
      Reads.pure(false) and
      Reads.pure(false)
    )(SlugInfo.apply _)

}

object ApiSlugInfoFormats extends ApiSlugInfoFormats