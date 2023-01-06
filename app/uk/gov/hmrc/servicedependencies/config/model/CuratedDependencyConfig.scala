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

package uk.gov.hmrc.servicedependencies.config.model

import play.api.libs.functional.syntax._
import play.api.libs.json.{__, Json, JsError, Reads}
import uk.gov.hmrc.servicedependencies.model.Version

case class DependencyConfig(
    name         : String
  , group        : String
  , latestVersion: Option[Version]
  )

object DependencyConfig {
  // Reads.failed not available in play-json 2.6
  private def failed[A](msg: String): Reads[A] =
    Reads[A] { _ => JsError(msg) }

  private def validateLatestVersion[C <: DependencyConfig](c: C): Reads[C] =
    (c.group.startsWith("uk.gov.hmrc"), c.latestVersion.isDefined) match {
      case (false, false) => failed("latestVersion is required for external (non 'uk.gov.hmrc') libraries")
      case _              => Reads.pure(c)
    }

  val reads: Reads[DependencyConfig] =
    ( (__ \ "name"         ).read[String]
    ~ (__ \ "group"        ).read[String]
    ~ (__ \ "latestVersion").readNullable[Version](Version.format)
    )(DependencyConfig.apply _)
      .flatMap(validateLatestVersion)
}

case class CuratedDependencyConfig(
    sbtPlugins: List[DependencyConfig]
  , libraries : List[DependencyConfig]
  , others    : List[DependencyConfig]
  ) {
    val allDependencies =
      sbtPlugins ++ libraries ++ others
  }

object CuratedDependencyConfig {
  val reads = {
    implicit val dcr = DependencyConfig.reads
    Json.reads[CuratedDependencyConfig]
    ( (__ \ "sbtPlugins").read[List[DependencyConfig]]
    ~ (__ \ "libraries" ).read[List[DependencyConfig]]
    ~ (__ \ "others"    ).read[List[DependencyConfig]]
    )(CuratedDependencyConfig.apply _)
  }
}
