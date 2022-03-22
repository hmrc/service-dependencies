/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.service

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, ImportedBy}
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, DependencyScope, LatestVersion, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.LatestVersionRepository

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class SlugDependenciesService @Inject()(
  slugInfoService          : SlugInfoService
, serviceDependenciesConfig: ServiceDependenciesConfig
, latestVersionRepository  : LatestVersionRepository
, serviceConfigsConnector  : ServiceConfigsConnector
, graphParser              : DependencyGraphParser
)(implicit ec: ExecutionContext
) {

  private lazy val curatedDependencyConfig: CuratedDependencyConfig =
    serviceDependenciesConfig.curatedDependencyConfig

  /*
   * We may want to evolve the model - but for this initial version we reuse the existing Dependency definition.
   */
  def curatedLibrariesOfSlug(name: String, flag: SlugInfoFlag): Future[Option[List[Dependency]]] =
    for {
      bobbyRules       <- serviceConfigsConnector.getBobbyRules
      latestVersions   <- latestVersionRepository.getAllEntries
      curatedLibraries <- curatedLibrariesOfSlug(name, flag, bobbyRules, latestVersions)
    } yield curatedLibraries

  def curatedLibrariesOfSlug(
    name          : String,
    flag          : SlugInfoFlag,
    bobbyRules    : BobbyRules,
    latestVersions: Seq[LatestVersion],
  ): Future[Option[List[Dependency]]] =
    slugInfoService.getSlugInfo(name, flag)
      .map(_.map(slugInfo =>
        if (slugInfo.dependencyDotCompile == "")
          curatedLibrariesOfSlugInfo(slugInfo, bobbyRules, latestVersions)
        else
          curatedLibrariesOfSlugInfoFromGraph(slugInfo, bobbyRules, latestVersions)
      ))

  def curatedLibrariesOfSlug(name: String, version: Version): Future[Option[List[Dependency]]] =
    for {
      bobbyRules       <- serviceConfigsConnector.getBobbyRules
      latestVersions   <- latestVersionRepository.getAllEntries
      curatedLibraries <- curatedLibrariesOfSlug(name, version, bobbyRules, latestVersions)
    } yield curatedLibraries

  def curatedLibrariesOfSlug(
    name          : String,
    version       : Version,
    bobbyRules    : BobbyRules,
    latestVersions: Seq[LatestVersion],
  ): Future[Option[List[Dependency]]] =
    slugInfoService.getSlugInfo(name, version)
      .map(_.map(slugInfo =>
        if (slugInfo.dependencyDotCompile == "")
          curatedLibrariesOfSlugInfo(slugInfo, bobbyRules, latestVersions)
        else
          curatedLibrariesOfSlugInfoFromGraph(slugInfo, bobbyRules, latestVersions)
      ))

  private def curatedLibrariesOfSlugInfo(
    slugInfo      : SlugInfo,
    bobbyRules    : BobbyRules,
    latestVersions: Seq[LatestVersion],
  ): List[Dependency] =
    slugInfo
      .dependencies
      .map { slugDependency =>
          val latestVersion =
            latestVersions
              .find(v => v.group == slugDependency.group && v.name == slugDependency.artifact)
              .map(_.version)
          Dependency(
              name                = slugDependency.artifact
            , group               = slugDependency.group
            , currentVersion      = slugDependency.version
            , latestVersion       = latestVersion
            , bobbyRuleViolations = bobbyRules.violationsFor(
                                        group   = slugDependency.group
                                      , name    = slugDependency.artifact
                                      , version = slugDependency.version
                                      )
            , scope               = Some(DependencyScope.Compile)
            )
      }
      .filter(dependency =>
        dependency.group.startsWith("uk.gov.hmrc") ||
        curatedDependencyConfig.allDependencies.exists(lib =>
          lib.name  == dependency.name &&
          lib.group == dependency.group
        ) ||
        dependency.bobbyRuleViolations.nonEmpty
      )

  private def curatedLibrariesOfSlugInfoFromGraph(
    slugInfo      : SlugInfo,
    bobbyRules    : BobbyRules,
    latestVersions: Seq[LatestVersion],
  ): List[Dependency] = {
    val compile    =  curatedLibrariesFromGraph(dotFileForScope(slugInfo, DependencyScope.Compile), slugInfo.name, latestVersions, bobbyRules, DependencyScope.Compile)
    val test       =  curatedLibrariesFromGraph(dotFileForScope(slugInfo, DependencyScope.Test   ), slugInfo.name, latestVersions, bobbyRules, DependencyScope.Test).filterNot(n => compile.exists(_.name == n.name))
    val build      =  curatedLibrariesFromGraph(dotFileForScope(slugInfo, DependencyScope.Build  ), slugInfo.name, latestVersions, bobbyRules, DependencyScope.Build)
    compile ++ test ++ build
  }

  def curatedLibrariesFromGraph(
    dotFile       : String,
    rootName      : String,
    latestVersions: Seq[LatestVersion],
    bobbyRules    : BobbyRules,
    scope         : DependencyScope
  ): List[Dependency] = {
    val graph = graphParser.parse(dotFile)
    val dependencies = graph
      .dependencies
      .filterNot(_.artefact == rootName)
      .map { graphDependency =>
        val latestVersion = latestVersions
            .find(v => v.group == graphDependency.group && v.name == graphDependency.artefact)
            .map(_.version)

        Dependency(
            name                = graphDependency.artefact
          , group               = graphDependency.group
          , currentVersion      = Version(graphDependency.version)
          , latestVersion       = latestVersion
          , bobbyRuleViolations = bobbyRules.violationsFor(
                                      group   = graphDependency.group
                                    , name    = graphDependency.artefact
                                    , version = Version(graphDependency.version)
                                  )
          , importBy            = graph.pathToRoot(graphDependency)
                                    .dropRight(1) // drop root node as its just the service jar itself
                                    .lastOption.map(n => ImportedBy(n.artefact, n.group, Version(n.version))) // the top level dep that imported it
                                    .filterNot(d => d.name == graphDependency.artefact && d.group == graphDependency.group) // filter out non-transient deps
          , scope               = Some(scope)
        )
      }.toList
    val parentDepsOfViolations  = dependencies.filter(d => d.importBy.nonEmpty && d.bobbyRuleViolations.nonEmpty).flatMap(_.importBy).toSet
    dependencies.filter(dependency =>
        (dependency.importBy.isEmpty && dependency.group.startsWith("uk.gov.hmrc")) ||                              // any directly imported HMRC dependency
          dependency.bobbyRuleViolations.nonEmpty ||                                                                // or any dependency with a bobby rule violation
          parentDepsOfViolations.contains(ImportedBy(dependency.name, dependency.group, dependency.currentVersion)) // or the parent that imported the violation
    )
  }

  private def dotFileForScope(slugInfo: SlugInfo, scope: DependencyScope) : String =
    scope match {
      case DependencyScope.Compile => slugInfo.dependencyDotCompile
      case DependencyScope.Test    => slugInfo.dependencyDotTest
      case DependencyScope.Build   => slugInfo.dependencyDotBuild
    }
}
