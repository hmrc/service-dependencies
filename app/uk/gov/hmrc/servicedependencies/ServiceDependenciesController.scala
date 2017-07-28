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

package uk.gov.hmrc.servicedependencies

import java.util.Date

import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.BlockingIOExecutionContext
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.service._


case class LibraryDependencyState(libraryName: String, currentVersion:Version, latestVersion: Option[Version])

case class RepositoryDependencies(repositoryName: String,
                                  libraryDependenciesState: Seq[LibraryDependencyState])
object RepositoryDependencies {
  implicit val ldsf = Json.format[LibraryDependencyState]
  implicit val format = Json.format[RepositoryDependencies]
}


trait ServiceDependenciesController extends BaseController {

	import BlockingIOExecutionContext._

  def libraryDependencyDataUpdatingService: LibraryDependencyDataUpdatingService

	implicit val environmentDependencyWrites = Json.writes[EnvironmentDependency]
	implicit val serviceDependenciesWrites = Json.writes[ServiceDependencies]

  def timeStampGenerator: () => Long = new Date().getTime


  def getDependencyVersionsForRepository(repositoryName: String) = Action.async {
		libraryDependencyDataUpdatingService.getDependencyVersionsForRepository(repositoryName)
      .map(maybeRepositoryDependencies =>
        maybeRepositoryDependencies.fold(
          NotFound(s"$repositoryName not found"))(repoDependencies => Ok(Json.toJson(repoDependencies))))
  }

  def reloadLibraryDependenciesForAllRepositories() = Action {
    libraryDependencyDataUpdatingService.reloadLibraryDependencyDataForAllRepositories(timeStampGenerator).map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure{
			case ex => throw new RuntimeException("reload of dependencies failed", ex)
		}
    Ok("Done")
	}


  def reloadLibraryVersions() = Action {
    libraryDependencyDataUpdatingService.reloadLibraryVersions(timeStampGenerator).map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure{
			case ex => throw new RuntimeException("reload of libraries failed", ex)
		}
    Ok("Done")
	}


  def libraries() = Action.async {

    libraryDependencyDataUpdatingService.getAllCuratedLibraries().map(versions => Ok(Json.toJson(versions)))
  }

  def dependencies() = Action.async {

    libraryDependencyDataUpdatingService.getAllRepositoriesDependencies().map(dependencies => Ok(Json.toJson(dependencies)))
  }

}

object ServiceDependenciesController extends ServiceDependenciesController {

  override def libraryDependencyDataUpdatingService: LibraryDependencyDataUpdatingService =
    new DefaultLibraryDependencyDataUpdatingService(config)


  protected val config = new ServiceDependenciesConfig("/dependency-versions-config.json")

  def boom() = Action {

    if(true)
      libraryDependencyDataUpdatingService.boom

    Ok

  }
}


