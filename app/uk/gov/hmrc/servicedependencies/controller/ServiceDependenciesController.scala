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

package uk.gov.hmrc.servicedependencies.controller

import cats.data.EitherT
import cats.implicits.*
import com.google.inject.{Inject, Singleton}
import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import play.api.mvc.*
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.connector.{DistinctVulnerability, ServiceConfigsConnector, TeamsAndRepositoriesConnector, VulnerabilitiesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency, DependencyBobbyRule}
import uk.gov.hmrc.servicedependencies.model.*
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDeployedDependencyRepository, DerivedLatestDependencyRepository, DerivedModuleRepository}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository}
import uk.gov.hmrc.servicedependencies.service.{CuratedLibrariesService, SlugInfoService, TeamDependencyService}

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceDependenciesController @Inject()(
  slugInfoService                    : SlugInfoService
, curatedLibrariesService            : CuratedLibrariesService
, serviceConfigsConnector            : ServiceConfigsConnector
, teamDependencyService              : TeamDependencyService
, metaArtefactRepository             : MetaArtefactRepository
, latestVersionRepository            : LatestVersionRepository
, derivedDeployedDependencyRepository: DerivedDeployedDependencyRepository
, derivedLatestDependencyRepository  : DerivedLatestDependencyRepository
, derivedModuleRepository            : DerivedModuleRepository
, teamsAndRepositoriesConnector      : TeamsAndRepositoriesConnector
, vulnerabilitiesConnector           : VulnerabilitiesConnector
, cc                                 : ControllerComponents
)(using
  ec : ExecutionContext
) extends BackendController(cc):

  given Writes[Dependencies] = Dependencies.writes

  def dependenciesForTeam(teamName: String): Action[AnyContent] =
    Action.async { implicit request =>
      for
        depsWithRules <- teamDependencyService.findAllDepsForTeam(teamName)
      yield Ok(Json.toJson(depsWithRules))
  }

  def metaArtefactDependencies(
    flag        : SlugInfoFlag,
    group       : String,
    artefact    : String,
    repoType    : Option[List[RepoType]],
    versionRange: Option[BobbyVersionRange],
    scope       : Option[List[DependencyScope]]
  ): Action[AnyContent] =
    Action.async { implicit request =>
      given Writes[MetaArtefactDependency] = MetaArtefactDependency.apiWrites
      for
        teamReposMap <- teamsAndRepositoriesConnector.cachedTeamToReposMap()
        dependencies <- flag match
                          case SlugInfoFlag.Latest => derivedLatestDependencyRepository.find(group = Some(group), artefact = Some(artefact), scopes = scope, repoType = repoType)
                          case _                   => derivedDeployedDependencyRepository.findWithDeploymentLookup(group = Some(group), artefact = Some(artefact), scopes = scope, flag = flag)
        results      =  versionRange
                          .map(range => dependencies.filter(s => range.includes(s.depVersion))).getOrElse(dependencies)
                          .map(repo => repo.copy(teams = teamReposMap.get(repo.repoName).toList.flatten.sorted))
      yield Ok(Json.toJson(results))
    }

  def getGroupArtefacts: Action[AnyContent] =
    Action.async:
      given Writes[GroupArtefacts] = GroupArtefacts.apiFormat
      slugInfoService
        .findGroupsArtefacts()
        .map(res => Ok(Json.toJson(res)))

  def slugInfo(name: String, version: Option[String]): Action[AnyContent] =
    Action.async:
      (for
         slugInfo        <- EitherT.fromOptionF(
                              version match
                                case Some(version) => slugInfoService.getSlugInfo(name, Version(version))
                                case None          => slugInfoService.getSlugInfo(name, SlugInfoFlag.Latest)
                            , NotFound("")
                            )
         optMetaArtefact <- EitherT.right[Result](metaArtefactRepository.find(name, slugInfo.version))
         optModule       =  optMetaArtefact.flatMap: meta =>
                              meta
                                .modules.find(_.name == name)     // Selecting the first one could be the 'it' module
                                .orElse(meta.modules.headOption)  // Fallback to encase module does not match service name (Java)
         slugInfoExtra   =  SlugInfoExtra(
           uri                   = slugInfo.uri
         , created               = slugInfo.created
         , name                  = slugInfo.name
         , version               = slugInfo.version
         , teams                 = slugInfo.teams
         , runnerVersion         = slugInfo.runnerVersion
         , classpath             = slugInfo.classpath
         , java                  = slugInfo.java
         , sbtVersion            = slugInfo.sbtVersion
         , repoUrl               = slugInfo.repoUrl
         , dependencies          = Nil
         , dependencyDotCompile  = optModule.flatMap(_.dependencyDotCompile).getOrElse("")
         , dependencyDotProvided = optModule.flatMap(_.dependencyDotProvided).getOrElse("")
         , dependencyDotTest     = optModule.flatMap(_.dependencyDotTest).getOrElse("")
         , dependencyDotIt       = optModule.flatMap(_.dependencyDotIt).getOrElse("")
         , dependencyDotBuild    = optMetaArtefact.flatMap(_.dependencyDotBuild).getOrElse("")
         , applicationConfig     = slugInfo.applicationConfig
         , slugConfig            = slugInfo.slugConfig
        )
       yield
        Ok(Json.toJson(slugInfoExtra)(SlugInfoExtra.write))
      ).merge

  def dependenciesOfSlugForTeam(team: String, flag: String): Action[AnyContent] =
    Action.async: request =>
      given Request[AnyContent] = request
      (for
         f    <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         deps <- EitherT.liftF[Future, Result, Map[String, Seq[Dependency]]]:
                   teamDependencyService.teamServiceDependenciesMap(team, f)
       yield
         implicit val dw = Dependency.writes
         Ok(Json.toJson(deps))
      ).merge

  def findJDKForEnvironment(team: Option[String], flag: String): Action[AnyContent] =
    Action.async: request =>
      given Request[AnyContent] = request
      (for
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         res <- EitherT.liftF[Future, Result, Seq[JDKVersion]]:
                  slugInfoService.findJDKVersions(team, f)
       yield
         given Writes[JDKVersion] = JDKVersionFormats.jdkVersionFormat
         Ok(Json.toJson(res))
      ).merge

  def findSBTForEnvironment(team: Option[String], flag: String): Action[AnyContent] =
    Action.async: request =>
      given Request[AnyContent] = request
      (for
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         res <- EitherT.liftF[Future, Result, Seq[SBTVersion]]:
                  slugInfoService.findSBTVersions(team, f)
       yield
         given Writes[SBTVersion] = SBTVersionFormats.sbtVersionFormat
         Ok(Json.toJson(res))
      ).merge

  def repositoryName(group: String, artefact: String, version: String): Action[AnyContent] =
    Action.async:
      derivedModuleRepository.findNameByModule(group, artefact, Version(version))
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res))))

  def moduleDependencies(repositoryName: String, versionOption: Option[String]): Action[AnyContent] =
    Action.async: request =>
      given Request[AnyContent] = request
      for
        latestVersions  <- latestVersionRepository.getAllEntries()
        bobbyRules      <- serviceConfigsConnector.getBobbyRules()
        vulnerabilities <- versionOption match
                             case Some(version) if version == "latest" => vulnerabilitiesConnector.vulnerabilitySummaries(serviceName = Some(repositoryName), flag = Some(SlugInfoFlag.Latest.asString))  //TODO lookup reponame for serviceName
                             case Some(version)                        => vulnerabilitiesConnector.vulnerabilitySummaries(serviceName = Some(repositoryName), version = Some(version))
                             case None                                 => Future.successful(Nil) // no vulnerabilities for libraries - services always pass in version
        metaArtefacts   <- versionOption match
                             case Some(version) if version == "latest" => metaArtefactRepository.find(repositoryName).map(_.toSeq)
                             case Some(version)                        => metaArtefactRepository.find(repositoryName, Version(version)).map(_.toSeq)
                             case None                                 => metaArtefactRepository.findAllVersions(repositoryName)
        repositories    =  metaArtefacts.map(toRepository(_, latestVersions, bobbyRules, vulnerabilities))
      yield
        given Writes[Repository] = Repository.writes
        Ok(Json.toJson(repositories))

  private def toRepository(meta: MetaArtefact, latestVersions: Seq[LatestVersion], bobbyRules: BobbyRules, vulnerabilities: Seq[DistinctVulnerability]) =
    def toDependencies(name: String, scope: DependencyScope, dotFile: String) =
      curatedLibrariesService.fromGraph(
        dotFile         = dotFile,
        rootName        = name,
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
      version     : Version,
      scope       : DependencyScope
    ): Seq[Dependency] =
      if !dependencies.exists(dep => dep.group == group && dep.name == artefact && dep.importBy.isEmpty)
      then
        Seq(Dependency(
          name                = artefact,
          group               = group,
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
          group    = "org.scala-lang",
          artefact = if v < Version("3.0.0") then "scala-library" else "scala3-library",
          version  = v,
          scope    = DependencyScope.Compile
        )

    val buildDependencies = meta.dependencyDotBuild.fold(Seq.empty[Dependency])(s => toDependencies(meta.name, DependencyScope.Build, s))

    Repository(
      name              = meta.name,
      version           = Some(meta.version),
      dependenciesBuild = buildDependencies ++
                            sbtVersion.toSeq.flatMap: v =>
                              directDependencyIfMissing(
                                buildDependencies,
                                group    = "org.scala-sbt",
                                artefact = "sbt",
                                version  = v,
                                scope    = DependencyScope.Build
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
                                    group   = m.group,
                                    name    = m.name,
                                    version = meta.version
                                  ).partition(rule => LocalDate.now().isAfter(rule.from))
                                RepositoryModule(
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

  def latestVersion(group: String, artefact: String): Action[AnyContent] =
    Action.async:
      latestVersionRepository.find(group, artefact)
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res)(LatestVersion.apiWrites))))

  def metaArtefact(repository: String, version: Option[String]): Action[AnyContent] =
    Action.async:
      version
        .fold(metaArtefactRepository.find(repository))(v => metaArtefactRepository.find(repository, Version(v)))
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res)(MetaArtefact.apiFormat))))

case class Repository(
  name             : String,
  version          : Option[Version], // optional since we don't have this when reshaping old data
  dependenciesBuild: Seq[Dependency],
  modules          : Seq[RepositoryModule]
)

object Repository:
  val writes: Writes[Repository] =
    given Writes[Dependency]       = Dependency.writes
    given Writes[RepositoryModule] = RepositoryModule.writes
    given Writes[Version]          = Version.format
    ( (__ \ "name"             ).write[String]
    ~ (__ \ "version"          ).writeNullable[Version]
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

import java.time.Instant

case class SlugInfoExtra(
  uri                  : String,
  created              : Instant,
  name                 : String,
  version              : Version,
  teams                : List[String],
  runnerVersion        : String,
  classpath            : String,
  java                 : JavaInfo,
  sbtVersion           : Option[String],
  repoUrl              : Option[String],
  dependencies         : List[SlugDependency],
  dependencyDotCompile : String,
  dependencyDotProvided: String,
  dependencyDotTest    : String,
  dependencyDotIt      : String,
  dependencyDotBuild   : String,
  applicationConfig    : String,
  slugConfig           : String
)

case class SlugDependency(
  path       : String,
  version    : Version,
  group      : String,
  artifact   : String,
  meta       : String = ""
)

object SlugInfoExtra:
  val write: Writes[SlugInfoExtra] =
    given Writes[SlugDependency] =
      ( (__ \ "path"    ).write[String]
      ~ (__ \ "version" ).write[Version](Version.format)
      ~ (__ \ "group"   ).write[String]
      ~ (__ \ "artifact").write[String]
      ~ (__ \ "meta"    ).write[String]
      )(sd => Tuple.fromProductTyped(sd))

    ( (__ \ "uri"                       ).write[String]
    ~ (__ \ "created"                   ).write[Instant]
    ~ (__ \ "name"                      ).write[String]
    ~ (__ \ "version"                   ).write[Version](Version.format)
    ~ (__ \ "teams"                     ).write[List[String]]
    ~ (__ \ "runnerVersion"             ).write[String]
    ~ (__ \ "classpath"                 ).write[String]
    ~ (__ \ "java"                      ).write[JavaInfo](ApiSlugInfoFormats.javaInfoFormat)
    ~ (__ \ "sbtVersion"                ).formatNullable[String]
    ~ (__ \ "repoUrl"                   ).formatNullable[String]
    ~ (__ \ "dependencies"              ).write[List[SlugDependency]]
    ~ (__ \ "dependencyDot" \ "compile" ).write[String]
    ~ (__ \ "dependencyDot" \ "provided").write[String]
    ~ (__ \ "dependencyDot" \ "test"    ).write[String]
    ~ (__ \ "dependencyDot" \ "it"      ).write[String]
    ~ (__ \ "dependencyDot" \ "build"   ).write[String]
    ~ (__ \ "applicationConfig"         ).write[String]
    ~ (__ \ "slugConfig"                ).write[String]
    )(sie => Tuple.fromProductTyped(sie))
