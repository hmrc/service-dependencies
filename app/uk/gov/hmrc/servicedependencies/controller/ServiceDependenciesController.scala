/*
 * Copyright 2019 HM Revenue & Customs
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

import cats.data.{EitherT, OptionT}
import cats.instances.all._
import cats.syntax.all._
import com.google.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.{Json, OFormat, OWrites}
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.connector.model.BobbyVersionRange
import uk.gov.hmrc.servicedependencies.controller.model.Dependencies
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.service.{DependencyDataUpdatingService, ServiceConfigsService, SlugInfoService}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceDependenciesController @Inject()(
  configuration                : Configuration,
  dependencyDataUpdatingService: DependencyDataUpdatingService,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  slugInfoService              : SlugInfoService,
  config                       : ServiceDependenciesConfig,
  serviceConfigsService        : ServiceConfigsService,
  cc                           : ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

  implicit val writes: OWrites[Dependencies] = Dependencies.writes

  def getDependencyVersionsForRepository(repositoryName: String): Action[AnyContent] =
    Action.async { implicit request =>
      (for {
         dependency   <- EitherT.fromOptionF(
                           dependencyDataUpdatingService
                             .getDependencyVersionsForRepository(repositoryName),
                           NotFound(s"$repositoryName not found"))
         depsWithRules <- EitherT.right[Result](serviceConfigsService.getDependenciesWithBobbyRules(dependency))
         res           =  Ok(Json.toJson(depsWithRules))
       } yield res
      ).merge
    }

  def dependencies(): Action[AnyContent] =
    Action.async { implicit request =>
      for {
        dependencies  <- dependencyDataUpdatingService
                           .getDependencyVersionsForAllRepositories()
        depsWithRules <- dependencies.toList
                          .traverse(serviceConfigsService.getDependenciesWithBobbyRules)
      } yield Ok(Json.toJson(depsWithRules))
    }

  def slugInfos(name: String, version: Option[String]): Action[AnyContent] =
    Action.async { implicit request =>
      implicit val format = ApiSlugInfoFormats.siFormat
      slugInfoService
        .getSlugInfos(name, version)
        .map(res => Ok(Json.toJson(res)))
    }

  def dependenciesForTeam(team: String): Action[AnyContent] =
    Action.async { implicit request =>
      for {
        teamRepos     <- teamsAndRepositoriesConnector.getTeam(team)
        deps          <- dependencyDataUpdatingService.getDependencyVersionsForAllRepositories()
        repos         =  teamRepos.getOrElse(Map())
        services      =  repos.getOrElse("Service", List())
        libraries     =  repos.getOrElse("Library", List())
        teamDeps      =  deps.filter(d => services.contains(d.repositoryName) || libraries.contains(d.repositoryName))
        depsWithRules <- teamDeps.toList
                           .traverse(serviceConfigsService.getDependenciesWithBobbyRules)
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

  def slugInfo(name: String, flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      SlugInfoFlag.parse(flag) match {
        case None    => Future(BadRequest("invalid flag"))
        case Some(f) => implicit val format = ApiSlugInfoFormats.siFormat
                        slugInfoService.getSlugInfo(name, f).map {
                          case None      => NotFound("")
                          case Some(res) => Ok(Json.toJson(res))
                        }
      }
    }

  def dependencyConfig(group: String, artefact: String, version: String): Action[AnyContent] =
    Action.async { implicit request =>
      slugInfoService
        .findDependencyConfig(group, artefact, version)
        .map { res =>
          val res2 = res.map(_.configs).getOrElse(Map.empty)
          Ok(Json.toJson(res2))
        }
    }

  def slugDependencyConfigs(name: String, flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      implicit val format: OFormat[DependencyConfig] = ApiSlugInfoFormats.dcFormat
      (for {
        flag     <- OptionT
                      .fromOption[Future](SlugInfoFlag.parse(flag))
                      .toRight(BadRequest("invalid flag"))
        slugInfo <- OptionT(slugInfoService.getSlugInfo(name, flag))
                      .toRight(NotFound(""))
        configs  <- EitherT.liftF[Future, Result, List[DependencyConfig]] {
                      slugInfo.classpathOrderedDependencies
                        .traverse(d => slugInfoService.findDependencyConfig(d.group, d.artifact, d.version))
                        .map(_.flatten)
                    }
       } yield Ok(Json.toJson(configs))
      ).merge
    }

  def findJDKForEnvironment(flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      SlugInfoFlag.parse(flag) match {
        case None    => Future(BadRequest("invalid flag"))
        case Some(f) => implicit val format = JDKVersionFormats.jdkFormat
                        slugInfoService
                          .findJDKVersions(f)
                          .map(res => Ok(Json.toJson(res)))
      }
    }
}
