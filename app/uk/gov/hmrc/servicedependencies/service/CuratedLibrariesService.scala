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
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, ImportedBy, DependencyBobbyRule}
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser
import uk.gov.hmrc.servicedependencies.model.*

import javax.inject.{Inject, Singleton}

@Singleton
class CuratedLibrariesService @Inject()(
  serviceDependenciesConfig: ServiceDependenciesConfig
):

  private lazy val curatedDependencyConfig: CuratedDependencyConfig =
    serviceDependenciesConfig.curatedDependencyConfig

  def toRepository(
    meta           : MetaArtefact
  , latestVersions : Seq[LatestVersion]
  , bobbyRules     : BobbyRules
  , vulnerabilities: Seq[DistinctVulnerability]
  ) =

    def toDependencies(moduleName: String, scope: DependencyScope, dotFile: String) =
      fromGraph(
        repoName        = meta.name,
        dotFile         = dotFile,
        moduleName      = moduleName,
        latestVersions  = latestVersions,
        bobbyRules      = bobbyRules,
        scope           = scope,
        subModuleNames  = meta.subModuleNames,
        vulnerabilities = vulnerabilities,
        buildInfo       = meta.buildInfo
      )

    val sbtVersion =
      // sbt-versions will be the same for all modules,
      meta.modules.flatMap(_.sbtVersion.toSeq).headOption

    def when[T](b: Boolean)(seq: => Seq[T]): Seq[T] =
      if b then seq else Nil

    def directDependencyIfMissing(
      dependencies: Seq[Dependency],
      group       : String,
      artefact    : String,
      scalaVersion: Option[String],
      version     : Version,
      scope       : DependencyScope
    ): Seq[Dependency] =
      if !dependencies.exists(dep => dep.group == group && dep.name == artefact && dep.scalaVersion == scalaVersion && dep.importBy.isEmpty)
      then
        Seq(Dependency(
          name                = artefact,
          group               = group,
          scalaVersion        = scalaVersion,
          currentVersion      = version,
          latestVersion       = latestVersions.find(d => d.name == artefact && d.group == group).map(_.version),
          bobbyRuleViolations = List.empty,
          vulnerabilities     = Seq.empty,
          importBy            = None,
          scope               = scope
        ))
      else
        Seq.empty

    // Note, for Scala 3, scala3-libary will be in the graph, but it's not necessarily a direct dependency.
    // We want a direct dependency so it will be present in the curated results.
    def addScalaDependencies(
      dependencies: Seq[Dependency],
      versions    : Seq[Version],
      scope       : DependencyScope
    ): Seq[Dependency] =
      versions.flatMap: v =>
        directDependencyIfMissing(
          dependencies,
          group        = "org.scala-lang",
          artefact     = if v < Version("3.0.0") then "scala-library" else "scala3-library",
          scalaVersion = None,
          version      = v,
          scope        = DependencyScope.Compile
        )

    val buildDependencies = meta.dependencyDotBuild.fold(Seq.empty[Dependency])(s => toDependencies(meta.name, DependencyScope.Build, s))

    CuratedLibrariesService.Repository(
      name              = meta.name,
      version           = meta.version,
      dependenciesBuild = buildDependencies ++
                            sbtVersion.toSeq.flatMap: v =>
                              directDependencyIfMissing(
                                buildDependencies,
                                group        = "org.scala-sbt",
                                artefact     = "sbt",
                                scalaVersion = None,
                                version      = v,
                                scope        = DependencyScope.Build
                              ),
      modules           = meta.modules
                              .filter(_.publishSkip.fold(true)(!_))
                              .map: m =>
                                val compileDependencies  = m.dependencyDotCompile .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Compile , s))
                                val providedDependencies = m.dependencyDotProvided.fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Provided, s))
                                val testDependencies     = m.dependencyDotTest    .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Test    , s))
                                val itDependencies       = m.dependencyDotIt      .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.It      , s))
                                val scalaVersions        = m.crossScalaVersions.toSeq.flatten
                                val (activeBobbyRuleViolations, pendingBobbyRuleViolations) =
                                  bobbyRules.violationsFor(
                                    group    = m.group,
                                    name     = m.name,
                                    version  = meta.version,
                                    repoName = meta.name
                                  ).partition(rule => java.time.LocalDate.now().isAfter(rule.from))
                                CuratedLibrariesService.RepositoryModule(
                                  name                 = m.name,
                                  group                = m.group,
                                  dependenciesCompile  = compileDependencies ++
                                                           when(compileDependencies.nonEmpty)(
                                                             addScalaDependencies(
                                                               compileDependencies,
                                                               scalaVersions,
                                                               scope = DependencyScope.Compile
                                                             )
                                                           ),
                                  dependenciesProvided = providedDependencies,
                                  dependenciesTest     = testDependencies ++
                                                           when(testDependencies.nonEmpty)(
                                                             addScalaDependencies(
                                                               testDependencies,
                                                               scalaVersions,
                                                               scope = DependencyScope.Compile
                                                             )
                                                           ),
                                  dependenciesIt       = itDependencies ++
                                                           when(itDependencies.nonEmpty)(
                                                             addScalaDependencies(
                                                               itDependencies,
                                                               scalaVersions,
                                                               scope = DependencyScope.Compile
                                                             )
                                                           ),
                                  crossScalaVersions   = m.crossScalaVersions,
                                  activeBobbyRuleViolations,
                                  pendingBobbyRuleViolations
                                )
    )

  private def fromGraph(
    repoName       : String,
    dotFile        : String,
    moduleName     : String,
    latestVersions : Seq[LatestVersion],
    bobbyRules     : BobbyRules,
    scope          : DependencyScope,
    subModuleNames : Seq[String],
    vulnerabilities: Seq[DistinctVulnerability],
    buildInfo      : Map[String, String]
  ): List[Dependency] =
    val graph = DependencyGraphParser.parse(dotFile)
    val dependencies = graph
      .dependencies
      .filterNot(x => x.artefact == moduleName || scope == DependencyScope.It && subModuleNames.contains(x.artefact)) // remove root or any submodules (for integration tests)
      .map: graphDependency =>
        val latestVersion =
          latestVersions
            .find(v => v.group == graphDependency.group && v.name == graphDependency.artefact)
            .map(_.version)

        Dependency(
          name                = graphDependency.artefact
        , group               = graphDependency.group
        , scalaVersion        = graphDependency.scalaVersion
        , currentVersion      = Version(graphDependency.version)
        , latestVersion       = latestVersion
        , bobbyRuleViolations = bobbyRules.violationsFor(
                                  group    = graphDependency.group
                                , name     = graphDependency.artefact
                                , version  = Version(graphDependency.version)
                                , repoName = repoName
                                )
        , vulnerabilities     = vulnerabilities
                                  .filter(_.matchesGav(graphDependency.group, graphDependency.artefact, graphDependency.version, graphDependency.scalaVersion))
                                  .map(_.id)
        , importBy            = graph.anyPathToRoot(graphDependency)
                                  .dropRight(if scope == DependencyScope.It && subModuleNames.nonEmpty then 2 else 1)     // drop root node as its just the service jar itself
                                  .lastOption.map(n => ImportedBy(n.artefact, n.group, Version(n.version)))               // the top level dep that imported it
                                  .filterNot(d => d.name == graphDependency.artefact && d.group == graphDependency.group) // filter out non-transient deps
        , scope               = scope
        )
      .toList

    val dependenciesToReturn =
      dependencies.filter: dependency =>
        (dependency.importBy.isEmpty && (
          dependency.group.startsWith("uk.gov.hmrc") ||
          curatedDependencyConfig.allDependencies.exists(d => d.group == dependency.group && d.name == dependency.name)
        ))                                                                                                             // any directly imported HMRC or curated dependency
          || dependency.bobbyRuleViolations.nonEmpty                                                                   // or any dependency with a bobby rule violation
          || dependency.vulnerabilities.nonEmpty                                                                       // or any dependency with a vulnerability

    val unreferencedVulnerableDependencies =
      if scope == DependencyScope.Compile then
        vulnerabilities
          .filterNot: v =>
            dependenciesToReturn.exists: d =>
              d.vulnerabilities.contains(v.id)
          .map: v =>
            Dependency(
              name                = v.vulnerableComponentName
            , group               = ""
            , scalaVersion        = None
            , currentVersion      = Version(v.vulnerableComponentVersion)
            , latestVersion       = None
            , bobbyRuleViolations = List.empty
            , vulnerabilities     = Seq(v.id)
            , importBy            = None
            , scope               = scope
            )
          .groupBy(x => (x.name, x.scope))
          .flatMap:(_, deps) =>
            deps.headOption.map(_.copy(vulnerabilities = deps.flatMap(_.vulnerabilities)))
      else
        Seq.empty

    val otherBuildDependencies =
      if scope == DependencyScope.Build
      then
        buildInfo.get("JAVA_VERSION").map: currentVersion =>
          Dependency(
            name                = "java"
          , group               = "com.java"
          , scalaVersion        = None
          , currentVersion      = Version(Version(currentVersion).major.toString)
          , latestVersion       = latestVersions.find(v => v.group == "com.java" && v.name == "java").map(x => Version(x.version.major.toString))
          , bobbyRuleViolations = List.empty
          , vulnerabilities     = Nil
          , importBy            = None
          , scope               = scope
          )
        ++ buildInfo.get("NODEJS_VERSION").map: currentVersion =>
          Dependency(
            name                = "nodejs"
          , group               = "org.nodejs"
          , scalaVersion        = None
          , currentVersion      = Version(currentVersion.stripPrefix("v") )
          , latestVersion       = latestVersions.find(v => v.group == "org.nodejs" && v.name == "nodejs").map(_.version)
          , bobbyRuleViolations = List.empty
          , vulnerabilities     = Nil
          , importBy            = None
          , scope               = scope
          )
      else Nil

    (dependenciesToReturn ++ unreferencedVulnerableDependencies ++ otherBuildDependencies)
      .sortBy(d => (d.name.contains("://"), d.scope.asString + d.group + d.name))

object CuratedLibrariesService:
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  case class Repository(
    name             : String,
    version          : Version,
    dependenciesBuild: Seq[Dependency],
    modules          : Seq[RepositoryModule]
  )

  object Repository:
    val writes: Writes[Repository] =
      given Writes[Dependency]       = Dependency.writes
      given Writes[RepositoryModule] = RepositoryModule.writes
      given Writes[Version]          = Version.format
      ( (__ \ "name"             ).write[String]
      ~ (__ \ "version"          ).write[Version]
      ~ (__ \ "dependenciesBuild").write[Seq[Dependency]]
      ~ (__ \ "modules"          ).write[Seq[RepositoryModule]]
      )(r => Tuple.fromProductTyped(r))

  case class RepositoryModule(
    name                : String,
    group               : String,
    dependenciesCompile : Seq[Dependency],
    dependenciesProvided: Seq[Dependency],
    dependenciesTest    : Seq[Dependency],
    dependenciesIt      : Seq[Dependency],
    crossScalaVersions  : Option[List[Version]],
    activeBobbyRules    : Seq[DependencyBobbyRule] = Seq(),
    pendingBobbyRules   : Seq[DependencyBobbyRule] = Seq()
  )

  object RepositoryModule:
    val writes: Writes[RepositoryModule] =
      given Writes[DependencyBobbyRule] = DependencyBobbyRule.writes
      given Writes[Dependency]          = Dependency.writes
      given Writes[Version]             = Version.format
      ( (__ \ "name"                ).write[String]
      ~ (__ \ "group"               ).write[String]
      ~ (__ \ "dependenciesCompile" ).write[Seq[Dependency]]
      ~ (__ \ "dependenciesProvided").write[Seq[Dependency]]
      ~ (__ \ "dependenciesTest"    ).write[Seq[Dependency]]
      ~ (__ \ "dependenciesIt"      ).write[Seq[Dependency]]
      ~ (__ \ "crossScalaVersions"  ).write[Seq[Version]].contramap[Option[Seq[Version]]](_.getOrElse(Seq.empty))
      ~ (__ \ "activeBobbyRules"    ).write[Seq[DependencyBobbyRule]]
      ~ (__ \ "pendingBobbyRules"   ).write[Seq[DependencyBobbyRule]]
      )(rm => Tuple.fromProductTyped(rm))
