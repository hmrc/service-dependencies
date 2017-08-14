/*
 * Copyright 2017 HM Revenue & Customs
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

import java.util.Date

import play.api.libs.json.Json

case class MongoSbtPluginVersion(sbtPluginName: String, version: Option[Version], updateDate: Long = new Date().getTime)

object MongoSbtPluginVersion {
  implicit val format = Json.format[MongoSbtPluginVersion]
}


case class SbtPluginVersion(sbtPluginName: String, version: Option[Version])

object SbtPluginVersion {
  implicit val format = Json.format[SbtPluginVersion]

  def apply(mongoSbtPluginVersion: MongoSbtPluginVersion): SbtPluginVersion = SbtPluginVersion(mongoSbtPluginVersion.sbtPluginName, mongoSbtPluginVersion.version)
}