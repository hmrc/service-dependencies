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

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json._
import uk.gov.hmrc.servicedependencies.service.DependencyService

case class MetaArtefactDependency(
                       slugName: String,
                       slugVersion: Version,
                       teams: List[String],
                       group: String,
                       artefact: String,
                       artefactVersion: Version,
                       compileFlag: Boolean,
                       providedFlag: Boolean,
                       testFlag: Boolean,
                       itFlag: Boolean,
                       buildFlag: Boolean
                     )

object MetaArtefactDependency {

  implicit val versionFormats: Format[Version] = Version.format

  val mongoFormat: OFormat[MetaArtefactDependency] = {
    ((__ \ "slugName").format[String]
      ~ (__ \ "slugVersion").format[Version]
      ~ (__ \ "group").format[String]
      ~ (__ \ "artefact").format[String]
      ~ (__ \ "artefactVersion").format[Version]
      ~ (__ \ "compileFlag").format[Boolean]
      ~ (__ \ "providedFlag").format[Boolean]
      ~ (__ \ "testFlag").format[Boolean]
      ~ (__ \ "itFlag").format[Boolean]
      ~ (__ \ "buildFlag").format[Boolean]
      )((sn, sv, g, a, av, cf, pf, tf, itf, bf) =>
      MetaArtefactDependency(
        sn, sv, List.empty, g, a, av, cf, pf, tf, itf, bf
      ),
      ( mad => (mad.slugName,
        mad.slugVersion,
        mad.group,
        mad.artefact,
        mad.artefactVersion,
        mad.compileFlag,
        mad.providedFlag,
        mad.testFlag,
        mad.itFlag,
        mad.buildFlag
      ))
    )
  }


  val apiWrites: OWrites[MetaArtefactDependency] = {

    implicit val depScope = DependencyScope.dependencyScopeFormat

    (
      (__ \ "slugName").write[String] and
      (__ \ "slugVersion").write[String] and
      (__ \ "teams").write[List[String]] and
      (__ \ "group").write[String] and
      (__ \ "artefact").write[String] and
      (__ \ "artefactVersion").write[String] and
      (__ \ "scopes").write[Set[DependencyScope]]
      ) (mad => (
      mad.slugName,
      mad.slugVersion.toString,
      mad.teams,
      mad.group,
      mad.artefact,
      mad.artefactVersion.toString,
        Set[DependencyScope](DependencyScope.Compile).filter(_ => mad.compileFlag) ++
        Set[DependencyScope](DependencyScope.Provided).filter(_ => mad.providedFlag) ++
        Set[DependencyScope](DependencyScope.Test).filter(_ => mad.testFlag) ++
        Set[DependencyScope](DependencyScope.It).filter(_ => mad.itFlag) ++
        Set[DependencyScope](DependencyScope.Build).filter(_ => mad.buildFlag)
    ))
  }
"scopes"
  def fromMetaArtefact(metaArtefact: MetaArtefact): Seq[MetaArtefactDependency] = {
    DependencyService.parseArtefactDependencies(metaArtefact).map {
      case (node, scope) =>
        MetaArtefactDependency(
          metaArtefact.name,
          metaArtefact.version,
          List.empty,
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

