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

package uk.gov.hmrc.servicedependencies.controller.model

import java.time.LocalDate

import org.joda.time.DateTime
import play.api.libs.json._
import uk.gov.hmrc.http.controllers.RestFormats
import uk.gov.hmrc.servicedependencies.connector.model.{BobbyVersion, BobbyVersionRange}
import uk.gov.hmrc.servicedependencies.model.Version

case class Dependency(
  name: String,
  currentVersion: Version,
  latestVersion: Option[Version],
  bobbyRuleViolations: List[DependencyBobbyRule],
  isExternal: Boolean = false
)

case class DependencyBobbyRule(
  reason: String,
  from: LocalDate,
  range: BobbyVersionRange
)

case class Dependencies(
  repositoryName: String,
  libraryDependencies: Seq[Dependency],
  sbtPluginsDependencies: Seq[Dependency],
  otherDependencies: Seq[Dependency],
  lastUpdated: DateTime
)

object Dependencies {
  val format = {
    implicit val dtr = RestFormats.dateTimeFormats
    implicit val dr = {
      implicit val vr   = Version.legacyApiWrites
      implicit val bvw  = Json.writes[BobbyVersion]
      implicit val bvrw = Json.writes[BobbyVersionRange]
      implicit val dbrw = Json.writes[DependencyBobbyRule]
      Json.writes[Dependency]
    }
    Json.writes[Dependencies]
  }
}
