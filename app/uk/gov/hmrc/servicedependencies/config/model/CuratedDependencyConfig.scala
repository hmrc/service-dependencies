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

package uk.gov.hmrc.servicedependencies.config.model

import play.api.libs.functional.syntax._
import play.api.libs.json.{__, Json, JsError, Reads}
import uk.gov.hmrc.servicedependencies.model.Version

trait DependencyConfig {
  def name         : String
  def group        : String
  def latestVersion: Option[Version]
}

case class SbtPluginConfig(
    name         : String
  , group        : String
  , latestVersion: Option[Version]
  ) extends DependencyConfig

case class LibraryConfig(
    name         : String
  , group        : String
  , latestVersion: Option[Version]
  ) extends DependencyConfig

case class OtherDependencyConfig(
    name         : String
  , group        : String
  , latestVersion: Option[Version]
  ) extends DependencyConfig

case class CuratedDependencyConfig(
    sbtPlugins       : List[SbtPluginConfig]
  , libraries        : List[LibraryConfig]
  , otherDependencies: List[OtherDependencyConfig]
  )

object CuratedDependencyConfig {

  // Reads.failed not available in play-json 2.6
  private def failed[A](msg: String): Reads[A] =
    Reads[A] { _ => JsError(msg) }

  private def optionalReads[A, B](f: A => Option[B], msg: => String)(a: A): Reads[B] =
    f(a) match {
      case Some(b) => Reads.pure(b)
      case None    => failed(msg)
    }

  private def optOptionalReads[A, B](f: A => Option[B], msg: => String)(o: Option[A]): Reads[Option[B]] =
    o match {
      case Some(b) => optionalReads(f, msg)(b).map(Some.apply)
      case None    => Reads.pure(None)
    }

  private def validateLatestVersion[C <: DependencyConfig](c: C): Reads[C] =
    (c.group.startsWith("uk.gov.hmrc"), c.latestVersion.isDefined) match {
      case (true , true ) => failed("latestVersion is not needed for internal ('uk.gov.hmrc') libraries")
      case (false, false) => failed("latestVersion is required for external (non 'uk.gov.hmrc') libraries")
      case _              => Reads.pure(c)
    }

  val otherReader: Reads[OtherDependencyConfig] =
    ( (__ \ "name"         ).read[String]
    ~ (__ \ "group"        ).read[String]
    ~ (__ \ "latestVersion").readNullable[String].flatMap(optOptionalReads(Version.parse, "invalid version"))
    )(OtherDependencyConfig.apply _)
      .flatMap(validateLatestVersion)

  val libraryReader: Reads[LibraryConfig] =
    ( (__ \ "name"         ).read[String]
    ~ (__ \ "group"        ).read[String]
    ~ (__ \ "latestVersion").readNullable[String].flatMap(optOptionalReads(Version.parse, "invalid version"))
    )(LibraryConfig.apply _)
      .flatMap(validateLatestVersion)

  val sbtPluginReader: Reads[SbtPluginConfig] =
    ( (__ \ "name"         ).read[String]
    ~ (__ \ "group"        ).read[String]
    ~ (__ \ "latestVersion").readNullable[String].flatMap(optOptionalReads(Version.parse, "invalid version"))
    )(SbtPluginConfig.apply _)
      .flatMap(validateLatestVersion)

  implicit val configReader = {
    implicit val or = otherReader
    implicit val lr = libraryReader
    implicit val pr = sbtPluginReader
    Json.reads[CuratedDependencyConfig]
  }
}
