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
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector, VulnerabilitiesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model.*
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDeployedDependencyRepository, DerivedLatestDependencyRepository, DerivedModuleRepository}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository}
import uk.gov.hmrc.servicedependencies.service.{CuratedLibrariesService, SlugInfoService, TeamDependencyService}

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
        repoMap      <- teamsAndRepositoriesConnector.cachedRepoMap()
        dependencies <- flag match
                          case SlugInfoFlag.Latest => derivedLatestDependencyRepository.find(group = Some(group), artefact = Some(artefact), scopes = scope, repoType = repoType)
                          case _                   => derivedDeployedDependencyRepository.findWithDeploymentLookup(group = Some(group), artefact = Some(artefact), scopes = scope, flag = flag)
        results      =  versionRange
                          .map(range => dependencies.filter(s => range.includes(s.depVersion)))
                          .getOrElse(dependencies)
                          .map: repo =>
                            repoMap.get(repo.repoName) match
                              case Some((teams, digitalService)) => repo.copy(teams = teams, digitalService = digitalService)
                              case _                             => repo

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

  def findJDKForEnvironment(team: Option[String], digitalService: Option[String], flag: String): Action[AnyContent] =
    Action.async: request =>
      given Request[AnyContent] = request
      (for
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         res <- EitherT.liftF[Future, Result, Seq[JDKVersion]]:
                  slugInfoService.findJDKVersions(team, digitalService, f)
       yield
         given Writes[JDKVersion] = JDKVersionFormats.jdkVersionFormat
         Ok(Json.toJson(res))
      ).merge

  def findSBTForEnvironment(team: Option[String], digitalService: Option[String], flag: String): Action[AnyContent] =
    Action.async: request =>
      given Request[AnyContent] = request
      (for
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         res <- EitherT.liftF[Future, Result, Seq[SBTVersion]]:
                  slugInfoService.findSBTVersions(team, digitalService, f)
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
        repositories    =  metaArtefacts.map(curatedLibrariesService.toRepository(_, latestVersions, bobbyRules, vulnerabilities))
      yield
        given Writes[CuratedLibrariesService.Repository] = CuratedLibrariesService.Repository.writes
        Ok(Json.toJson(repositories))

  def latestVersion(group: String, artefact: String): Action[AnyContent] =
    Action.async:
      latestVersionRepository.find(group, artefact)
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res)(LatestVersion.apiWrites))))

  def metaArtefact(repository: String, version: Option[String]): Action[AnyContent] =
    Action.async:
      version
        .fold(metaArtefactRepository.find(repository))(v => metaArtefactRepository.find(repository, Version(v)))
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res)(MetaArtefact.apiFormat))))

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
