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
import play.api.libs.json.{Json, OFormat}

case class SlugDependency(
  libraryName: String,
  version    : String,
  group      : String,
  artifact   : String)

case class SlugInfo(
  slugUri      : String,
  slugName     : String,
  slugVersion  : String,
  runnerVersion: String,
  classpath    : String,
  dependencies : Seq[SlugDependency])

object SlugDependency {
  implicit val format: OFormat[SlugDependency] = Json.format[SlugDependency]
}

object SlugInfo {
  implicit val format: OFormat[SlugInfo] = Json.format[SlugInfo]
}
