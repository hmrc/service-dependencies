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

import play.api.libs.json.{Format, JsError, JsResult, JsSuccess, JsValue, Json, Writes}

case class Team(
                 name: String,
                 repos: Map[String, Seq[String]]
               ) {
  def allRepos: Seq[String] =
    repos.values.toSeq.flatten

  private def findRepo(name: String): Seq[String] = repos.getOrElse(name, Seq.empty)

  def services   : Seq[String] = findRepo("Service")
  def libraries  : Seq[String] = findRepo("Library")
  def others     : Seq[String] = findRepo("Other")
  def prototypes : Seq[String] = findRepo("Prototype")

}

object Team {
  case class ApiTeam(name: String,
                     repos: Option[Map[String, Seq[String]]])

  object ApiTeam {
    implicit val format = Json.format[ApiTeam]
  }

  implicit val format: Format[Team] = new Format[Team] {
    override def reads(json: JsValue): JsResult[Team] =
      json.validate[ApiTeam] match {
        case JsSuccess(apiTeam, path) =>
          val team = Team(apiTeam.name, apiTeam.repos.getOrElse(Map.empty))
          JsSuccess(team, path)
        case error: JsError => error
      }

    override def writes(team: Team): JsValue = Json.writes[Team].writes(team)
  }

  def normalisedName(name: String): String = name.toLowerCase.replaceAll(" ", "_")
}
