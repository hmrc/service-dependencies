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
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.service._


case class LibraryDependencyState(libraryName: String, currentVersion:Version, latestVersion: Option[Version])
case class SbtPluginDependencyState(sbtPluginName: String, currentVersion:Version, latestVersion: Option[Version])

case class RepositoryDependencies(repositoryName: String,
                                  libraryDependenciesState: Seq[LibraryDependencyState],
                                  sbtPluginsDependenciesState: Seq[SbtPluginDependencyState])
object RepositoryDependencies {
  implicit val ldsf = Json.format[LibraryDependencyState]
  implicit val spdsf = Json.format[SbtPluginDependencyState]
  implicit val format = Json.format[RepositoryDependencies]
}

trait ServiceDependenciesController extends BaseController {

	import BlockingIOExecutionContext._

  def dependencyDataUpdatingService: DependencyDataUpdatingService

	implicit val environmentDependencyWrites = Json.writes[EnvironmentDependency]
	implicit val serviceDependenciesWrites = Json.writes[ServiceDependencies]

  def timeStampGenerator: () => Long = new Date().getTime


  def getDependencyVersionsForRepository(repositoryName: String) = Action.async {
		dependencyDataUpdatingService.getDependencyVersionsForRepository(repositoryName)
      .map(maybeRepositoryDependencies =>
        maybeRepositoryDependencies.fold(
          NotFound(s"$repositoryName not found"))(repoDependencies => Ok(Json.toJson(repoDependencies))))
  }

  private val resultDone = Ok("Done")

  def reloadLibraryDependenciesForAllRepositories() = Action {
    dependencyDataUpdatingService.reloadDependenciesDataForAllRepositories(timeStampGenerator).map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure{
			case ex => throw new RuntimeException("reload of dependencies failed", ex)
		}
    resultDone
	}


  def reloadLibraryVersions() = Action {
    dependencyDataUpdatingService.reloadLibraryVersions(timeStampGenerator).map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure{
			case ex => throw new RuntimeException("reload of libraries failed", ex)
		}
    resultDone
	}

  def reloadSbtPluginVersions() = Action {
    dependencyDataUpdatingService.reloadSbtPluginVersions(timeStampGenerator).map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure{
			case ex => throw new RuntimeException("reload of sbt plugins failed", ex)
		}
    resultDone
	}


  def libraries() = Action.async {

    dependencyDataUpdatingService.getAllCuratedLibraries().map(versions => Ok(Json.toJson(versions)))
  }

  def dependencies() = Action.async {

    dependencyDataUpdatingService.getAllRepositoriesDependencies().map(dependencies => Ok(Json.toJson(dependencies)))
  }

}

object ServiceDependenciesController extends ServiceDependenciesController {

  override def dependencyDataUpdatingService: DependencyDataUpdatingService =
    new DefaultDependencyDataUpdatingService(config)


  protected val config = new ServiceDependenciesConfig("/dependency-versions-config.json")

}


