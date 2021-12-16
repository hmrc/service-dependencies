/*
 * Copyright 2021 HM Revenue & Customs
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
import cats.instances.all._
import com.google.inject.{Inject, Singleton}
import play.api.libs.functional.syntax._
import play.api.libs.json.{__, Json, OWrites}
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository}
import uk.gov.hmrc.servicedependencies.service.{RepositoryDependenciesService, SlugDependenciesService, SlugInfoService, TeamDependencyService}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceDependenciesController @Inject()(
  slugInfoService              : SlugInfoService
, slugDependenciesService      : SlugDependenciesService
, serviceConfigsConnector      : ServiceConfigsConnector
, teamDependencyService        : TeamDependencyService
, repositoryDependenciesService: RepositoryDependenciesService
, metaArtefactRepository       : MetaArtefactRepository
, latestVersionRepository      : LatestVersionRepository
, cc                           : ControllerComponents
)(implicit ec: ExecutionContext
) extends BackendController(cc) {

  implicit val dw: OWrites[Dependencies] = Dependencies.writes

  def getDependencyVersionsForRepository(repositoryName: String): Action[AnyContent] =
    Action.async {
      (for {
         dependencies  <- EitherT.fromOptionF(
                            repositoryDependenciesService
                             .getDependencyVersionsForRepository(repositoryName)
                          , NotFound(s"$repositoryName not found")
                          )
       } yield Ok(Json.toJson(dependencies))
      ).merge
    }

  def dependencies(): Action[AnyContent] =
    Action.async {
      for {
        dependencies <- repositoryDependenciesService
                          .getDependencyVersionsForAllRepositories
      } yield Ok(Json.toJson(dependencies))
    }

  def dependenciesForTeam(teamName: String): Action[AnyContent] =
    Action.async { implicit request =>
      for {
        depsWithRules <- teamDependencyService.findAllDepsForTeam(teamName)
      } yield Ok(Json.toJson(depsWithRules))
  }

  def getServicesWithDependency(
    flag        : String,
    group       : String,
    artefact    : String,
    versionRange: String,
    scope       : Option[String]
  ): Action[AnyContent] =
    Action.async { implicit request =>
      implicit val format = ApiServiceDependencyFormats.serviceDependencyFormat
      (for {
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         sc  <- scope match {
                  case None     => EitherT.pure[Future, Result](None)
                  case Some(sc) => EitherT.fromEither[Future](DependencyScope.parse(sc))
                                     .bimap(BadRequest(_), Some.apply)
                }
         vr  <- EitherT.fromOption[Future](BobbyVersionRange.parse(versionRange), BadRequest(s"invalid versionRange '$versionRange'"))
         res <- EitherT.right[Result] {
                  slugInfoService
                    .findServicesWithDependency(f, group, artefact, vr, sc)
                }
       } yield Ok(Json.toJson(res))
      ).merge
    }

  def getGroupArtefacts: Action[AnyContent] =
    Action.async {
      implicit val format = GroupArtefacts.apiFormat
      slugInfoService.findGroupsArtefacts
        .map(res => Ok(Json.toJson(res)))
    }

  def slugInfos(name: String, version: Option[String]): Action[AnyContent] =
    Action.async {
      implicit val format = ApiSlugInfoFormats.slugInfoFormat
      slugInfoService
        .getSlugInfos(name, version.map(Version.apply))
        .map(res => Ok(Json.toJson(res)))
    }

  def slugInfo(name: String, flag: String): Action[AnyContent] =
    Action.async {
      (for {
         f        <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         slugInfo <- EitherT.fromOptionF(slugInfoService.getSlugInfo(name, f), NotFound(""))
       } yield {
         implicit val sif = ApiSlugInfoFormats.slugInfoFormat
         Ok(Json.toJson(slugInfo))
       }
      ).merge
    }

  def dependenciesOfSlug(name: String, flag: String): Action[AnyContent] =
    Action.async {
      (for {
         f    <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         deps <- EitherT.fromOptionF(slugDependenciesService.curatedLibrariesOfSlug(name, f), NotFound(""))
       } yield {
         implicit val dw = Dependency.writes
         Ok(Json.toJson(deps))
       }
      ).merge
    }

  def dependenciesOfSlugForTeam(team: String, flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      (for {
         f    <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         deps <- EitherT.liftF[Future, Result, Map[String, Seq[Dependency]]](
                   teamDependencyService.dependenciesOfSlugsForTeam(team, f)
                 )
       } yield {
         implicit val dw = Dependency.writes
         Ok(Json.toJson(deps))
       }
      ).merge
    }

  def findJDKForEnvironment(flag: String): Action[AnyContent] =
    Action.async {
      (for {
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         res <- EitherT.liftF[Future, Result, Seq[JDKVersion]](
                  slugInfoService.findJDKVersions(f)
                )
       } yield {
         implicit val jdkvf = JDKVersionFormats.jdkVersionFormat
         Ok(Json.toJson(res))
       }
      ).merge
    }

  def moduleRepository(group: String, artefact: String, version: String): Action[AnyContent] =
    Action.async {
      metaArtefactRepository.findRepoNameByModule(group, artefact, Version(version))
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res))))
    }

  def getRepositoryModules(repositoryName: String): Action[AnyContent] =
    Action.async {
      (for {
         meta           <- EitherT.fromOptionF(
                             metaArtefactRepository.find(repositoryName),
                             NotFound("")
                           )
         latestVersions <- EitherT.liftF[Future, Result, Seq[MongoLatestVersion]](latestVersionRepository.getAllEntries)
         bobbyRules     <- EitherT.liftF[Future, Result, BobbyRules](serviceConfigsConnector.getBobbyRules)
       } yield {
         def toDependencies(name: String, scope: DependencyScope, dotFile: String) =
           slugDependenciesService.curatedLibrariesFromGraph(
             dotFile        = dotFile,
             rootName       = name,
             latestVersions = latestVersions,
             bobbyRules     = bobbyRules,
             scope          = scope
           )
         val repository =
           Repository(
             name              = meta.name,
             version           = meta.version,
             dependenciesBuild = meta.dependencyDotBuild.fold(Seq.empty[Dependency])(s => toDependencies(meta.name, DependencyScope.Build, s)),
             modules           = meta.modules.map { m =>
                                   RepositoryModule(
                                     name                = m.name,
                                     group               = m.group,
                                     dependenciesCompile = m.dependencyDotCompile.fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Compile, s)),
                                     dependenciesTest    = m.dependencyDotTest   .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Test   , s))
                                   )
                                 }
           )
         implicit val rw = Repository.writes
         Ok(Json.toJson(repository))
       }
      ).merge
    }
}

case class Repository(
  name             : String,
  version          : Version,
  dependenciesBuild: Seq[Dependency],
  modules          : Seq[RepositoryModule]
)

object Repository {
  val writes: OWrites[Repository] = {
    implicit val dw  = Dependency.writes
    implicit val rmw = RepositoryModule.writes
    ( (__ \ "name"             ).write[String]
    ~ (__ \ "version"          ).write[Version](Version.format)
    ~ (__ \ "dependenciesBuild").write[Seq[Dependency]]
    ~ (__ \ "modules"          ).write[Seq[RepositoryModule]]
    )(unlift(Repository.unapply))
  }
}

case class RepositoryModule(
  name               : String,
  group              : String,
  dependenciesCompile: Seq[Dependency],
  dependenciesTest   : Seq[Dependency]
)

object RepositoryModule {
  val writes: OWrites[RepositoryModule] = {
    implicit val dw = Dependency.writes
    ( (__ \ "name"               ).write[String]
    ~ (__ \ "group"              ).write[String]
    ~ (__ \ "dependenciesCompile").write[Seq[Dependency]]
    ~ (__ \ "dependenciesTest"   ).write[Seq[Dependency]]
    )(unlift(RepositoryModule.unapply))
  }
}
