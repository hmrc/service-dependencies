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
import uk.gov.hmrc.servicedependencies.model.RepoType.Service
import uk.gov.hmrc.servicedependencies.service.DependencyService

case class MetaArtefactDependency(
  repoName: String,
  repoVersion: Version,
  teams: List[String], // Override on write
  repoType: RepoType,
  depGroup: String,
  depArtefact: String,
  depVersion: Version,
  compileFlag: Boolean,
  providedFlag: Boolean,
  testFlag: Boolean,
  itFlag: Boolean,
  buildFlag: Boolean
)

object MetaArtefactDependency {

  implicit val versionFormats: Format[Version] = Version.format

  val mongoFormat: OFormat[MetaArtefactDependency] = {
    ((__ \ "repoName").format[String]
      ~ (__ \ "repoVersion").format[Version]
      ~ (__ \ "repoType").format[RepoType]
      ~ (__ \ "group").format[String]
      ~ (__ \ "artefact").format[String]
      ~ (__ \ "version").format[Version]
      ~ (__ \ "scope_compile").format[Boolean]
      ~ (__ \ "scope_provided").format[Boolean]
      ~ (__ \ "scope_test").format[Boolean]
      ~ (__ \ "scope_it").format[Boolean]
      ~ (__ \ "scope_build").format[Boolean]
      )((sn, sv, rt, g, a, av, cf, pf, tf, itf, bf) =>
      MetaArtefactDependency(
        sn, sv, List.empty, rt, g, a, av, cf, pf, tf, itf, bf
      ),
      ( mad => (mad.repoName,
        mad.repoVersion,
        mad.repoType,
        mad.depGroup,
        mad.depArtefact,
        mad.depVersion,
        mad.compileFlag,
        mad.providedFlag,
        mad.testFlag,
        mad.itFlag,
        mad.buildFlag
      ))
    )
  }


  val apiWrites: OWrites[MetaArtefactDependency] = {

    implicit val scopeFormat   = DependencyScope.dependencyScopeFormat

    (
      (__ \ "repoName").write[String] ~
      (__ \ "repoVersion").write[String] ~
      (__ \ "teams").write[List[String]] ~
      (__ \ "repoType").write[RepoType] ~
      (__ \ "depGroup").write[String] ~
      (__ \ "depArtefact").write[String] ~
      (__ \ "depVersion").write[String] ~
      (__ \ "scopes").write[Set[DependencyScope]]
      ) (mad => (
      mad.repoName,
      mad.repoVersion.toString,
      mad.teams,
      mad.repoType,
      mad.depGroup,
      mad.depArtefact,
      mad.depVersion.toString,
        Set[DependencyScope](DependencyScope.Compile).filter(_ => mad.compileFlag) ++
        Set[DependencyScope](DependencyScope.Provided).filter(_ => mad.providedFlag) ++
        Set[DependencyScope](DependencyScope.Test).filter(_ => mad.testFlag) ++
        Set[DependencyScope](DependencyScope.It).filter(_ => mad.itFlag) ++
        Set[DependencyScope](DependencyScope.Build).filter(_ => mad.buildFlag)
    ))
  }

  def fromMetaArtefact(metaArtefact: MetaArtefact, repoType: RepoType): Seq[MetaArtefactDependency] = {
    DependencyService.parseArtefactDependencies(metaArtefact).map {
      case (node, scope) =>
        MetaArtefactDependency(
          metaArtefact.name,
          metaArtefact.version,
          List.empty,
          repoType,
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

  def fromServiceDependency(serviceDependency: ServiceDependency): MetaArtefactDependency = {
    serviceDependency match {
      case ServiceDependency(slugName, slugVersion, teams, depGroup, depArtefact, depVersion, _, scope) =>
        MetaArtefactDependency(
          repoName        = slugName,
          repoVersion     = slugVersion,
          teams           = teams,
          repoType        = Service,
          depGroup        = depGroup,
          depArtefact     = depArtefact,
          depVersion      = depVersion,
          compileFlag     = scope.contains(DependencyScope.Compile),
          providedFlag    = scope.contains(DependencyScope.Provided),
          testFlag        = scope.contains(DependencyScope.Test),
          itFlag          = scope.contains(DependencyScope.It),
          buildFlag       = scope.contains(DependencyScope.Build)
        )
    }
  }
}

