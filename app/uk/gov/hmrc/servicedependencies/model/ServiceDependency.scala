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

import play.api.libs.json.{__, OFormat}
import play.api.libs.functional.syntax._

case class ServiceDependency(
    slugName    : String,
    slugVersion : String,
    teams       : List[String],
    depGroup    : String,
    depArtefact : String,
    depVersion  : String) {
  lazy val depSemanticVersion: Option[Version] =
    Version.parse(depVersion)
}

trait ApiServiceDependencyFormats {

  val derivedMongoFormat: OFormat[ServiceDependency] =
    ( (__ \ "slugName"     ).format[String]
      ~ (__ \ "slugVersion").format[String]
      ~ (__ \ "teams"      ).formatWithDefault[List[String]](List.empty[String])
      ~ (__ \ "group"      ).format[String]
      ~ (__ \ "artefact"   ).format[String]
      ~ (__ \ "version"    ).format[String]
      )(ServiceDependency.apply, unlift(ServiceDependency.unapply))

  val sdFormat: OFormat[ServiceDependency] =
    ( (__ \ "slugName"   ).format[String]
    ~ (__ \ "slugVersion").format[String]
    ~ (__ \ "teams"      ).format[List[String]]
    ~ (__ \ "depGroup"   ).format[String]
    ~ (__ \ "depArtefact").format[String]
    ~ (__ \ "depVersion" ).format[String]
    )(ServiceDependency.apply, unlift(ServiceDependency.unapply))
}

object ApiServiceDependencyFormats extends ApiServiceDependencyFormats