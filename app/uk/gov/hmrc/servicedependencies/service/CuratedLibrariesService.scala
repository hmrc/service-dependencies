/*
 * Copyright 2023 HM Revenue & Customs
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

import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig
import uk.gov.hmrc.servicedependencies.connector.DistinctVulnerability
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, ImportedBy}
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser
import uk.gov.hmrc.servicedependencies.model.*

import javax.inject.{Inject, Singleton}

@Singleton
class CuratedLibrariesService @Inject()(
  serviceDependenciesConfig: ServiceDependenciesConfig
):

  private lazy val curatedDependencyConfig: CuratedDependencyConfig =
    serviceDependenciesConfig.curatedDependencyConfig

  def fromGraph(
    dotFile        : String,
    rootName       : String,
    latestVersions : Seq[LatestVersion],
    bobbyRules     : BobbyRules,
    scope          : DependencyScope,
    subModuleNames : Seq[String],
    vulnerabilities: Seq[DistinctVulnerability]
  ): List[Dependency] =
    //if scope == DependencyScope.Compile then println(s">>>>>> From Graph: $scope, $vulnerabilities")
    val graph = DependencyGraphParser.parse(dotFile)
    val dependencies = graph
      .dependencies
      .filterNot(x => x.artefact == rootName || scope == DependencyScope.It && subModuleNames.contains(x.artefact)) // remove root or any submodules (for integration tests)
      .map: graphDependency =>
        //if scope == DependencyScope.Compile then println(s">>>>>> graphDependency: $graphDependency")
        val latestVersion =
          latestVersions
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
                                ).filterNot:
                                  _.exemptProjects.contains(rootName)
        , vulnerabilities     = vulnerabilities
                                  .filter: a =>
                                    //if graphDependency.group.contains("com.typesafe.play") && graphDependency.artefact.contains("play") && a.vulnerableComponentName.contains("play")
                                    //then println(s">>>>>> IS ${graphDependency} in $a ?  " + a.matchesGav(graphDependency.group, graphDependency.artefact, graphDependency.version))
                                    a.matchesGav(graphDependency.group, graphDependency.artefact, graphDependency.version)
                                  .map(_.id)
        , importBy            = graph.anyPathToRoot(graphDependency)
                                  .dropRight(if scope == DependencyScope.It && subModuleNames.nonEmpty then 2 else 1) // drop root node as its just the service jar itself
                                  .lastOption.map(n => ImportedBy(n.artefact, n.group, Version(n.version))) // the top level dep that imported it
                                  .filterNot(d => d.name == graphDependency.artefact && d.group == graphDependency.group) // filter out non-transient deps
        , scope               = scope
        )
      .toList

    val parentDepsOfViolations =
      dependencies
        .filter: d =>
          d.importBy.nonEmpty
            && (
              d.bobbyRuleViolations.nonEmpty
                || vulnerabilities.exists: v =>
                  v.matchesGav(d.group, d.name, d.currentVersion.original)
            )
        .flatMap(_.importBy)
        .toSet

    val dependenciesToReturn =
      dependencies.filter: dependency =>
        (dependency.importBy.isEmpty && (
          dependency.group.startsWith("uk.gov.hmrc") ||
          curatedDependencyConfig.allDependencies.exists(d => d.group == dependency.group && d.name == dependency.name)
        ))                                                                                                             // any directly imported HMRC or curated dependency
          || dependency.bobbyRuleViolations.nonEmpty                                                                   // or any dependency with a bobby rule violation
          || parentDepsOfViolations.contains(ImportedBy(dependency.name, dependency.group, dependency.currentVersion)) // or the parent that imported the violation
          || vulnerabilities.exists: v =>
              v.matchesGav(dependency.group, dependency.name, dependency.currentVersion.original)

    val unreferencedVulnerableDependencies =
      if scope == DependencyScope.Compile then
        vulnerabilities
          .filterNot: v =>
            dependenciesToReturn.exists: d =>
              v.matchesGav(d.group, d.name, d.currentVersion.original)
          .map: v =>
            println(s"Unreferenced: $v")
            Dependency(
              name                = v.vulnerableComponentName.split(":").last
            , group               = v.vulnerableComponentName.split(":").dropRight(1).mkString(":")
            , currentVersion      = Version(v.vulnerableComponentVersion)
            , latestVersion       = None
            , bobbyRuleViolations = List.empty
            , vulnerabilities     = Seq(v.id)
            , importBy            = None
            , scope               = scope
            )
      else
        Seq.empty

    (dependenciesToReturn ++ unreferencedVulnerableDependencies)
      .sortBy(d => (d.group.contains("://"), d.scope.asString + d.group + d.name))
