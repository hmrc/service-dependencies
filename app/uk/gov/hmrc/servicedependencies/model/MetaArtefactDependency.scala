/*
 * Copyright 2024 HM Revenue & Customs
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

import play.api.libs.json.{Format, Json, OFormat}
import uk.gov.hmrc.servicedependencies.service.DependencyService

case class MetaArtefactDependency(
                       slugName: String,
                       group: String,
                       artefact: String,
                       version: Version,
                       compileFlag: Boolean,
                       providedFlag: Boolean,
                       testFlag: Boolean,
                       itFlag: Boolean,
                       buildFlag: Boolean,
                     )

object MetaArtefactDependency {

  implicit val versionFormats: Format[Version] = Version.format

  implicit val format: OFormat[MetaArtefactDependency] = Json.format[MetaArtefactDependency]

  def fromMetaArtefact(metaArtefact: MetaArtefact): Seq[MetaArtefactDependency] = {
    DependencyService.parseArtefactDependencies(metaArtefact).map {
      case (node, scope) =>
        MetaArtefactDependency(
          metaArtefact.name,
          node.group,
          node.artefact,
          Version(node.version),
          compileFlag   = scope.contains(DependencyScope.Compile),
          providedFlag  = scope.contains(DependencyScope.Provided),
          testFlag      = scope.contains(DependencyScope.Test),
          itFlag        = scope.contains(DependencyScope.It),
          buildFlag     = scope.contains(DependencyScope.Build)
        )
    }.toSeq
  }
}

