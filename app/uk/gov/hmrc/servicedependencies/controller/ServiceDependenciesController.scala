/*
 * Copyright 2020 HM Revenue & Customs
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
import play.api.libs.json.{Json, OWrites}
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.service.{DependencyDataUpdatingService, RepositoryDependenciesService, SlugDependenciesService, SlugInfoService, TeamDependencyService}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceDependenciesController @Inject()(
  dependencyDataUpdatingService: DependencyDataUpdatingService
, slugInfoService              : SlugInfoService
, slugDependenciesService      : SlugDependenciesService
, serviceConfigsConnector      : ServiceConfigsConnector
, teamDependencyService        : TeamDependencyService
, repositoryDependenciesService: RepositoryDependenciesService
, cc                           : ControllerComponents
)(implicit ec: ExecutionContext
) extends BackendController(cc) {

  implicit val dw: OWrites[Dependencies] = Dependencies.writes

  def getDependencyVersionsForRepository(repositoryName: String): Action[AnyContent] =
    Action.async { implicit request =>
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
    Action.async { implicit request =>
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

  def getServicesWithDependency(flag: String, group: String, artefact: String, versionRange: String): Action[AnyContent] =
    Action.async { implicit request =>
      implicit val format = ApiServiceDependencyFormats.sdFormat
      (for {
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         vr  <- EitherT.fromOption[Future](BobbyVersionRange.parse(versionRange), BadRequest(s"invalid versionRange '$versionRange'"))
         res <- EitherT.right[Result] {
                  slugInfoService
                    .findServicesWithDependency(f, group, artefact, vr)
                }
      } yield Ok(Json.toJson(res))
      ).merge
    }

  def getGroupArtefacts: Action[AnyContent] =
    Action.async { implicit request =>
      implicit val format = GroupArtefacts.apiFormat
      slugInfoService.findGroupsArtefacts
        .map(res => Ok(Json.toJson(res)))
    }

  def slugInfos(name: String, version: Option[String]): Action[AnyContent] =
    Action.async { implicit request =>
      implicit val format = ApiSlugInfoFormats.siFormat
      slugInfoService
        .getSlugInfos(name, version)
        .map(res => Ok(Json.toJson(res)))
    }

  def slugInfo(name: String, flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      (for {
         f        <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         slugInfo <- EitherT.fromOptionF(slugInfoService.getSlugInfo(name, f), NotFound(""))
       } yield {
         implicit val sif = ApiSlugInfoFormats.siFormat
         Ok(Json.toJson(slugInfo))
       }
      ).merge
    }

  def dependenciesOfSlug(name: String, flag: String): Action[AnyContent] =
    Action.async { implicit request =>
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
                   teamDependencyService.dependenciesOfSlugForTeam(team, f)
                 )
       } yield {
         implicit val dw = Dependency.writes
         Ok(Json.toJson(deps))
       }
      ).merge
    }

  def findJDKForEnvironment(flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      (for {
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         res <- EitherT.liftF[Future, Result, Seq[JDKVersion]](
                  slugInfoService.findJDKVersions(f)
                )
       } yield {
         implicit val jdkvf = JDKVersionFormats.jdkFormat
         Ok(Json.toJson(res))
       }
      ).merge
    }
}
