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

import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

case class MetaArtefactDependency(
  repoName    : String,
  repoVersion : Version,
  repoType    : RepoType,
  teams       : List[String], // Override on write
  depGroup    : String,
  depArtefact : String,
  depVersion  : Version,
  // TODO scala versions ??
  compileFlag : Boolean,
  providedFlag: Boolean,
  testFlag    : Boolean,
  itFlag      : Boolean,
  buildFlag   : Boolean
)

object MetaArtefactDependency {

  def apply(metaArtefact: MetaArtefact, repoType: RepoType, node: DependencyGraphParser.Node, scopes: Set[DependencyScope]): MetaArtefactDependency = MetaArtefactDependency(
    repoName     = metaArtefact.name,
    repoVersion  = metaArtefact.version,
    repoType     = repoType,
    teams        = List.empty,
    depGroup     = node.group,
    depArtefact  = node.artefact,
    depVersion   = Version(node.version),
    compileFlag  = scopes.contains(DependencyScope.Compile),
    providedFlag = scopes.contains(DependencyScope.Provided),
    testFlag     = scopes.contains(DependencyScope.Test),
    itFlag       = scopes.contains(DependencyScope.It),
    buildFlag    = scopes.contains(DependencyScope.Build)
  )

  implicit val versionFormats: Format[Version] = Version.format

  val mongoFormat: OFormat[MetaArtefactDependency] =
    ( (__ \ "repoName"      ).format[String]
    ~ (__ \ "repoVersion"   ).format[Version]
    ~ (__ \ "repoType"      ).format[RepoType]
    ~ (__ \ "group"         ).format[String]
    ~ (__ \ "artefact"      ).format[String]
    ~ (__ \ "version"       ).format[Version]
    ~ (__ \ "scope_compile" ).format[Boolean]
    ~ (__ \ "scope_provided").format[Boolean]
    ~ (__ \ "scope_test"    ).format[Boolean]
    ~ (__ \ "scope_it"      ).format[Boolean]
    ~ (__ \ "scope_build"   ).format[Boolean]
    )((sn, sv, rt, g, a, av, cf, pf, tf, itf, bf) =>
      MetaArtefactDependency(sn, sv, rt, List.empty, g, a, av, cf, pf, tf, itf, bf), ( mad =>
        (
          mad.repoName,
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
        )
      )
    )

  val apiWrites: OWrites[MetaArtefactDependency] = {

    implicit val scopeFormat   = DependencyScope.dependencyScopeFormat

    (
      (__ \ "repoName").write[String] ~
      (__ \ "repoVersion").write[String] ~
      (__ \ "repoType").write[RepoType] ~
      (__ \ "teams").write[List[String]] ~
      (__ \ "depGroup").write[String] ~
      (__ \ "depArtefact").write[String] ~
      (__ \ "depVersion").write[String] ~
      (__ \ "scopes").write[Set[DependencyScope]]
      ) (mad => (
      mad.repoName,
      mad.repoVersion.toString,
      mad.repoType,
      mad.teams,
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
}

