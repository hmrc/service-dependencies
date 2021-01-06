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

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class Team(
    name: String
  , repos: Map[String, Seq[String]]
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
  val format =
    ( (__ \ "name" ).format[String]
    ~ (__ \ "repos").formatNullable[Map[String, Seq[String]]]
                    .inmap[Map[String, Seq[String]]](_.getOrElse(Map.empty), Some.apply)
    )(Team.apply, unlift(Team.unapply))
}
