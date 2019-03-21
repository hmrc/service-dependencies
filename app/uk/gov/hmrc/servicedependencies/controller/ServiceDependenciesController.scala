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
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.controller.model.Dependencies
import uk.gov.hmrc.servicedependencies.model.{ApiServiceDependencyFormats, ApiSlugInfoFormats, DependencyConfig, GroupArtefacts, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.service.{DependencyDataUpdatingService, SlugInfoService}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceDependenciesController @Inject()(
  configuration                : Configuration,
  dependencyDataUpdatingService: DependencyDataUpdatingService,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  slugInfoService              : SlugInfoService,
  config                       : ServiceDependenciesConfig,
  cc                           : ControllerComponents
  )(implicit ec: ExecutionContext)
    extends BackendController(cc) {

  val logger = LoggerFactory.getLogger(this.getClass)

  implicit val dependenciesFormat = Dependencies.format

  def getDependencyVersionsForRepository(repositoryName: String) =
    Action.async { implicit request =>
      dependencyDataUpdatingService
        .getDependencyVersionsForRepository(repositoryName)
        .map {
          case None      => NotFound(s"$repositoryName not found")
          case Some(res) => Ok(Json.toJson(res))
        }
    }

  def dependencies() =
    Action.async { implicit request =>
      dependencyDataUpdatingService.getDependencyVersionsForAllRepositories
        .map(res => Ok(Json.toJson(res)))
    }

  def slugInfos(name: String, version: Option[String]) =
    Action.async { implicit request =>
      implicit val format = ApiSlugInfoFormats.siFormat
      slugInfoService
        .getSlugInfos(name, version)
        .map(res => Ok(Json.toJson(res)))
    }

  def dependenciesForTeam(team: String) =
    Action.async { implicit request =>
      for {
        teamRepos <- teamsAndRepositoriesConnector.getTeam(team)
        deps      <- dependencyDataUpdatingService.getDependencyVersionsForAllRepositories()
        repos     =  teamRepos.getOrElse(Map())
        services  =  repos.getOrElse("Service", List())
        libraries =  repos.getOrElse("Library", List())
        teamDeps  =  deps.filter(d => services.contains(d.repositoryName) || libraries.contains(d.repositoryName))
      } yield Ok(Json.toJson(teamDeps))
    }

  def getServicesWithDependency(flag: String, group: String, artefact: String) =
    Action.async { implicit request =>
      SlugInfoFlag.parse(flag) match {
        case None       => Future(BadRequest("invalid flag"))
        case Some(flag) => implicit val format = ApiServiceDependencyFormats.sdFormat
                           slugInfoService
                             .findServicesWithDependency(flag, group, artefact)
                             .map(res => Ok(Json.toJson(res)))
      }
    }

  def getGroupArtefacts =
    Action.async { implicit request =>
      implicit val format = GroupArtefacts.apiFormat
      slugInfoService.findGroupsArtefacts
        .map(res => Ok(Json.toJson(res)))
    }

  def slugInfo(name: String, flag: String) =
    Action.async { implicit request =>
      SlugInfoFlag.parse(flag) match {
        case None       => Future(BadRequest("invalid flag"))
        case Some(flag) => implicit val format = ApiSlugInfoFormats.siFormat
                           slugInfoService.getSlugInfo(name, flag).map {
                             case None      => NotFound("")
                             case Some(res) => Ok(Json.toJson(res))
                           }
      }
    }

  def dependencyConfig(group: String, artefact: String, version: String) =
    Action.async { implicit request =>
      slugInfoService
        .findDependencyConfig(group, artefact, version)
        .map { res =>
          val res2 = res.map(_.configs).getOrElse(Map.empty)
          Ok(Json.toJson(res2))
        }
    }

  def slugDependencyConfigs(name: String, flag: String) =
    Action.async { implicit request =>
      implicit val format = ApiSlugInfoFormats.dcFormat
      (for {
         flag     <- OptionT.fromOption[Future](SlugInfoFlag.parse(flag))
                       .toRight(BadRequest("invalid flag"))
         slugInfo <- OptionT(slugInfoService.getSlugInfo(name, flag))
                       .toRight(NotFound(""))
         configs  <- EitherT.liftT[Future, Result, List[DependencyConfig]] {
                      slugInfo.classpathOrderedDependencies
                         .traverse(d => slugInfoService.findDependencyConfig(d.group, d.artifact, d.version))
                         .map(_.flatten)
                    }
       } yield Ok(Json.toJson(configs))
      ).merge
    }
}
