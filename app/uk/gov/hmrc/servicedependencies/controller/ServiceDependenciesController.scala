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

import com.google.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.controller.model.Dependencies
import uk.gov.hmrc.servicedependencies.service._

@Singleton
class ServiceDependenciesController @Inject()(
  configuration: Configuration,
  dependencyDataUpdatingService: DependencyDataUpdatingService,
  config: ServiceDependenciesConfig,
  cc: ControllerComponents)
    extends BackendController(cc) {

  val logger = LoggerFactory.getLogger(this.getClass)

  implicit val dependenciesFormat = Dependencies.format

  def getDependencyVersionsForRepository(repositoryName: String) =
    Action.async { implicit request =>
      dependencyDataUpdatingService
        .getDependencyVersionsForRepository(repositoryName)
        .map { case None      => NotFound(s"$repositoryName not found")
               case Some(res) => Ok(Json.toJson(res))
             }
    }

  def dependencies() =
    Action.async { implicit request =>
      dependencyDataUpdatingService
        .getDependencyVersionsForAllRepositories
        .map(res => Ok(Json.toJson(res)))
    }

  def slugInfos(name: String, version: Option[String]) =
    Action.async { implicit request =>
      dependencyDataUpdatingService
        .getSlugInfos(name, version)
        .map(res => Ok(Json.toJson(res)))
    }
}
