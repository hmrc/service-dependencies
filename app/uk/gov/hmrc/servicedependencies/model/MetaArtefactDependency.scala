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

import play.api.libs.functional.syntax._
import play.api.libs.json._

import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

case class MetaArtefactDependency(
  repoName      : String,
  repoVersion   : Version,
  repoType      : RepoType,
  teams         : List[String],   // Added on write
  digitalService: Option[String], // Added on write
  depGroup      : String,
  depArtefact   : String,
  depVersion    : Version,
  compileFlag   : Boolean,
  providedFlag  : Boolean,
  testFlag      : Boolean,
  itFlag        : Boolean,
  buildFlag     : Boolean
):
  val depScopes: Set[DependencyScope] =
    DependencyScope
      .values
      .toSet
      .filter:
        case DependencyScope.Compile  => compileFlag
        case DependencyScope.Provided => providedFlag
        case DependencyScope.Test     => testFlag
        case DependencyScope.It       => itFlag
        case DependencyScope.Build    => buildFlag

object MetaArtefactDependency:

  def apply(
    metaArtefact: MetaArtefact,
    repoType    : RepoType,
    node        : DependencyGraphParser.Node,
    scopes      : Set[DependencyScope]
  ): MetaArtefactDependency =
    MetaArtefactDependency(
      repoName       = metaArtefact.name,
      repoVersion    = metaArtefact.version,
      repoType       = repoType,
      teams          = List.empty,
      digitalService = None,
      depGroup       = node.group,
      depArtefact    = node.artefact,
      depVersion     = Version(node.version),
      compileFlag    = scopes.contains(DependencyScope.Compile),
      providedFlag   = scopes.contains(DependencyScope.Provided),
      testFlag       = scopes.contains(DependencyScope.Test),
      itFlag         = scopes.contains(DependencyScope.It),
      buildFlag      = scopes.contains(DependencyScope.Build)
    )

  private def ignore[A]: OWrites[A] =
    _ => Json.obj()

  val mongoFormat: Format[MetaArtefactDependency] =
    ( (__ \ "repoName"      ).format[String]
    ~ (__ \ "repoVersion"   ).format[Version](Version.format)
    ~ (__ \ "repoType"      ).format[RepoType]
    ~ OFormat(
        Reads.pure(List.empty[String])
      , ignore[List[String]]
      )
    ~ OFormat(
        Reads.pure(Option.empty[String])
      , ignore[Option[String]]
      )
    ~ (__ \ "group"         ).format[String]
    ~ (__ \ "artefact"      ).format[String]
    ~ (__ \ "version"       ).format[Version](Version.format)
    ~ (__ \ "scope_compile" ).format[Boolean]
    ~ (__ \ "scope_provided").format[Boolean]
    ~ (__ \ "scope_test"    ).format[Boolean]
    ~ (__ \ "scope_it"      ).format[Boolean]
    ~ (__ \ "scope_build"   ).format[Boolean]
    )(MetaArtefactDependency.apply, mad => Tuple.fromProductTyped(mad))

  val apiWrites: Writes[MetaArtefactDependency] =
    given Writes[DependencyScope] = DependencyScope.dependencyScopeFormat
    ( (__ \ "repoName"      ).write[String]
    ~ (__ \ "repoVersion"   ).write[String]
    ~ (__ \ "repoType"      ).write[RepoType]
    ~ (__ \ "teams"         ).write[List[String]]
    ~ (__ \ "digitalService").writeNullable[String]
    ~ (__ \ "depGroup"      ).write[String]
    ~ (__ \ "depArtefact"   ).write[String]
    ~ (__ \ "depVersion"    ).write[String]
    ~ (__ \ "scopes"        ).write[Set[DependencyScope]]
    )(mad => (
      mad.repoName,
      mad.repoVersion.original,
      mad.repoType,
      mad.teams,
      mad.digitalService,
      mad.depGroup,
      mad.depArtefact,
      mad.depVersion.original,
      mad.depScopes
    ))

