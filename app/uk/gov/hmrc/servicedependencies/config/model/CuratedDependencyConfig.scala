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
import play.api.libs.json.{JsPath, Json, Reads}
import uk.gov.hmrc.servicedependencies.model.Version

case class OtherDependencyConfig(name: String, latestVersion: Option[Version])

case class CuratedDependencyConfig(
  sbtPlugins: Seq[SbtPluginConfig],
  libraries: Seq[String],
  otherDependencies: Seq[OtherDependencyConfig])
object CuratedDependencyConfig {

  implicit val otherReader: Reads[OtherDependencyConfig] =
    ((JsPath \ "name").read[String] and
      (JsPath \ "latestVersion").readNullable[String])((name, version) =>
      OtherDependencyConfig(name, version.map(Version.parse)))

  implicit val pluginsReader: Reads[SbtPluginConfig] = (
    (JsPath \ "org").read[String] and
      (JsPath \ "name").read[String] and
      (JsPath \ "version").readNullable[String]
  )((org, name, version) => SbtPluginConfig(org, name, version.map(Version.parse)))

  implicit val configReader = Json.reads[CuratedDependencyConfig]
}

case class SbtPluginConfig(org: String, name: String, version: Option[Version]) {
  def isInternal() = version.isEmpty
  def isExternal() = !isInternal()

}
