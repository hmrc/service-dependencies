/*
 * Copyright 2017 HM Revenue & Customs
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
import play.api.mvc.Action
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.servicedependencies.service.DependencyDataUpdatingService

@Singleton
class AdministrationController @Inject()(dependencyDataUpdatingService: DependencyDataUpdatingService) extends BaseController {

  def reloadLibraryDependenciesForAllRepositories() = Action {
    implicit request =>
      dependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories().map(_ => Logger.debug(s"""${">" * 10} done ${"<" * 10}""")).onFailure {
        case ex => throw new RuntimeException("reload of dependencies failed", ex)
      }
      Ok("Done")
  }


  def reloadLibraryVersions() = Action {
    implicit request =>
      dependencyDataUpdatingService.reloadLatestLibraryVersions().map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure {
        case ex => throw new RuntimeException("reload of libraries failed", ex)
      }
      Ok("Done")
  }

  def reloadSbtPluginVersions() = Action {
    implicit request =>
      dependencyDataUpdatingService.reloadLatestSbtPluginVersions().map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure {
        case ex => throw new RuntimeException("reload of sbt plugins failed", ex)
      }
      Ok("Done")
  }

  def dropCollection(collection: String) = Action.async {
    implicit request =>
      dependencyDataUpdatingService.dropCollection(collection).map(_ => Ok(s"$collection dropped"))
  }

  def clearUpdateDates = Action.async {
    implicit request =>
      dependencyDataUpdatingService.clearUpdateDates.map(rs => Ok(s"${rs.size} records updated"))
  }

}
