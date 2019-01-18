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

package uk.gov.hmrc.servicedependencies.controller.admin

import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, NewSlugParserJob}
import uk.gov.hmrc.servicedependencies.service.{DependencyDataUpdatingService, SlugJobProcessor}

import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class AdministrationController @Inject()(
    dependencyDataUpdatingService: DependencyDataUpdatingService,
    slugJobProcessor             : SlugJobProcessor,
    cc                           : ControllerComponents)
  extends BackendController(cc) {

  def reloadLibraryDependenciesForAllRepositories(force: Option[Boolean] = None) = Action { implicit request =>
    dependencyDataUpdatingService
      .reloadCurrentDependenciesDataForAllRepositories(force = force.getOrElse(false))
      .map(_ => Logger.debug(s"""${">" * 10} done ${"<" * 10}"""))
      .onFailure {
        case ex => throw new RuntimeException("reload of dependencies failed", ex)
      }
    Ok("Done")
  }

  def reloadLibraryVersions() = Action { implicit request =>
    dependencyDataUpdatingService
      .reloadLatestLibraryVersions()
      .map(_ => Logger.debug(s"""${">" * 10} done ${"<" * 10}"""))
      .onFailure {
        case ex => throw new RuntimeException("reload of libraries failed", ex)
      }
    Ok("Done")
  }

  def reloadSbtPluginVersions() = Action { implicit request =>
    dependencyDataUpdatingService
      .reloadLatestSbtPluginVersions()
      .map(_ => Logger.debug(s"""${">" * 10} done ${"<" * 10}"""))
      .onFailure {
        case ex => throw new RuntimeException("reload of sbt plugins failed", ex)
      }
    Ok("Done")
  }

  def dropCollection(collection: String) = Action.async { implicit request =>
    dependencyDataUpdatingService.dropCollection(collection).map(_ => Ok(s"$collection dropped"))
  }

  def clearUpdateDates = Action.async { implicit request =>
    dependencyDataUpdatingService.clearUpdateDates.map(rs => Ok(s"${rs.size} records updated"))
  }

  def mongoLocks() = Action.async { implicit request =>
    dependencyDataUpdatingService.locks().map(locks => Ok(Json.toJson(locks)))
  }

  // TODO should be invoked from S3 notification? Requires moving out of admin?
  def addSlugParserJob =
    Action.async(parse.json) { implicit request =>
      withJsonBody[NewSlugParserJob] { newJob =>
        dependencyDataUpdatingService.addSlugParserJob(newJob)
          .map { case true  => Created
                 case false => Conflict
               }
          .recover{
            case ex => throw new RuntimeException("creation of slug job failed", ex)
          }
      }
    }

  def processSlugParserJobs =
    Action { implicit request =>
      Logger.info("Running slug parser jobs")
      slugJobProcessor.run()
        .recover {
          case NonFatal(e) => Logger.error(s"An error occurred processing slug parser jobs: ${e.getMessage}", e)
        }
      Accepted
    }

  def executeJob =
    Action.async(parse.json) { implicit request =>
      withJsonBody[MongoSlugParserJob] { job =>
        slugJobProcessor.processJob(job)
          .recover {
            case NonFatal(e) => Logger.error(s"An error occurred processing slug parser job ${job.slugUri}: ${e.getMessage}", e)
          }
        Future(Accepted)
      }
    }
}
