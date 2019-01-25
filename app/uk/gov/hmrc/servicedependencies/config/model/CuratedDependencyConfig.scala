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

package uk.gov.hmrc.servicedependencies.config.model

import play.api.libs.functional.syntax._
import play.api.libs.json.{__, Json, JsError, Reads}
import uk.gov.hmrc.servicedependencies.model.Version

case class OtherDependencyConfig(name: String, latestVersion: Option[Version])

case class CuratedDependencyConfig(
  sbtPlugins       : Seq[SbtPluginConfig],
  libraries        : Seq[String],
  otherDependencies: Seq[OtherDependencyConfig])

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

  implicit val otherReader: Reads[OtherDependencyConfig] =
    ( (__ \ "name"         ).read[String]
    ~ (__ \ "latestVersion").readNullable[String].flatMap(optOptionalReads(Version.parse, "invalid version"))
    )(OtherDependencyConfig.apply _)

  implicit val pluginsReader: Reads[SbtPluginConfig] =
    ( (__ \ "org"    ).read[String]
    ~ (__ \ "name"   ).read[String]
    ~ (__ \ "version").readNullable[String].flatMap(optOptionalReads(Version.parse, "invalid version"))
    )(SbtPluginConfig.apply _)

  implicit val configReader =
    Json.reads[CuratedDependencyConfig]
}

case class SbtPluginConfig(org: String, name: String, version: Option[Version]) {
  def isInternal = version.isEmpty
  def isExternal = !isInternal
}
