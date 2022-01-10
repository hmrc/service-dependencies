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
         f     <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         deps  <- EitherT.fromOptionF(slugDependenciesService.curatedLibrariesOfSlug(name, f), NotFound(""))
                  // previously we collected dependencies from the jars in thes slug (i.e. only Compile time dependencies available)
                  // if we detect this we are using old data, for the case of Latest, we can use the data from github - which includes
                  // all library dependencies (compile and test) as well as plugin dependencies
         deps2 <- if (f == SlugInfoFlag.Latest && !deps.exists(_.scope.contains(DependencyScope.Build))) { // i.e. no dependency graph data yet
                    EitherT.fromOptionF(
                       repositoryDependenciesService.getDependencyVersionsForRepository(name),
                       NotFound("")
                    ).map { latestGithub =>
                      val compile = deps
                      // test dependencies are assumed to be any library dependencies that are not in the slug
                      val test    = latestGithub.libraryDependencies.filterNot(ghd => deps.exists(d => d.group == ghd.group && d.name == ghd.name))
                      val build   = latestGithub.sbtPluginsDependencies ++ latestGithub.otherDependencies // other includes sbt.version
                      compile.map(_.copy(scope = Some(DependencyScope.Compile))) ++
                        build.map(_.copy(scope = Some(DependencyScope.Build))) ++
                        test.map(_.copy(scope = Some(DependencyScope.Test)))
                    }
                  } else EitherT.pure[Future, Result](deps)
       } yield {
         implicit val dw = Dependency.writes
         Ok(Json.toJson(deps2))
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

  def repositoryName(group: String, artefact: String, version: String): Action[AnyContent] =
    Action.async {
      metaArtefactRepository.findRepoNameByModule(group, artefact, Version(version))
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res))))
    }

  def moduleDependencies(repositoryName: String): Action[AnyContent] =
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
             version           = Some(meta.version),
             dependenciesBuild = meta.dependencyDotBuild.fold(Seq.empty[Dependency])(s => toDependencies(meta.name, DependencyScope.Build, s)),
             modules           = meta.modules
                                   .filter(_.publishSkip.fold(true)(!_))
                                   .map { m =>
                                     RepositoryModule(
                                       name                = m.name,
                                       group               = m.group,
                                       dependenciesCompile = m.dependencyDotCompile.fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Compile, s)),
                                       dependenciesTest    = m.dependencyDotTest   .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Test   , s)),
                                       crossScalaVersions  = m.crossScalaVersions
                                     )
                                   },
             sbtVersion        = meta.modules.find(_.sbtVersion.isDefined).flatMap(_.sbtVersion) // sbt-versions will be the same for all modules
           )
         implicit val rw = Repository.writes
         Ok(Json.toJson(repository))
       }
      ).leftFlatMap { _ =>
        // fallback to data from getDependencyVersionsForRepository()
        implicit val rw = Repository.writes
        for {
          dependencies <- EitherT.fromOptionF(
                            repositoryDependenciesService.getDependencyVersionsForRepository(repositoryName),
                            NotFound("")
                         )
        } yield
          Ok(
            Json.toJson(
              Repository(
                name              = repositoryName,
                version           = None,
                dependenciesBuild = dependencies.sbtPluginsDependencies,
                modules           = Seq(
                                      RepositoryModule(
                                        name                = repositoryName,
                                        group               = "uk.gov.hmrc",
                                        dependenciesCompile = dependencies.libraryDependencies,
                                        dependenciesTest    = Seq.empty[Dependency],
                                        crossScalaVersions  = None
                                      )
                                    ),
                sbtVersion        = None
              )
            )
          )
      }
      .merge
    }
}

case class Repository(
  name             : String,
  version          : Option[Version], // optional since we don't have this when reshaping old data
  dependenciesBuild: Seq[Dependency],
  modules          : Seq[RepositoryModule],
  sbtVersion       : Option[Version]
)

object Repository {
  val writes: OWrites[Repository] = {
    implicit val dw  = Dependency.writes
    implicit val rmw = RepositoryModule.writes
    implicit val vf  = Version.format
    ( (__ \ "name"             ).write[String]
    ~ (__ \ "version"          ).writeNullable[Version]
    ~ (__ \ "dependenciesBuild").write[Seq[Dependency]]
    ~ (__ \ "modules"          ).write[Seq[RepositoryModule]]
    ~ (__ \ "sbtVersion"       ).writeNullable[Version]
    )(unlift(Repository.unapply))
  }
}

case class RepositoryModule(
  name               : String,
  group              : String,
  dependenciesCompile: Seq[Dependency],
  dependenciesTest   : Seq[Dependency],
  crossScalaVersions : Option[List[Version]]
)

object RepositoryModule {
  val writes: OWrites[RepositoryModule] = {
    implicit val dw = Dependency.writes
    implicit val vf  = Version.format
    ( (__ \ "name"               ).write[String]
    ~ (__ \ "group"              ).write[String]
    ~ (__ \ "dependenciesCompile").write[Seq[Dependency]]
    ~ (__ \ "dependenciesTest"   ).write[Seq[Dependency]]
    ~ (__ \ "crossScalaVersions" ).write[Seq[Version]].contramap[Option[Seq[Version]]](_.getOrElse(Seq.empty))
    )(unlift(RepositoryModule.unapply))
  }
}
