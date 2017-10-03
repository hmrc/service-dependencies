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

import com.google.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.service._


case class LibraryDependencyState(libraryName: String, currentVersion:Version, latestVersion: Option[Version])
case class SbtPluginDependencyState(sbtPluginName: String, currentVersion:Version, latestVersion: Option[Version], isExternal: Boolean)
case class OtherDependencyState(name: String, currentVersion:Version, latestVersion: Option[Version])
                                

case class RepositoryDependencies(repositoryName: String,
                                  libraryDependenciesState: Seq[LibraryDependencyState],
                                  sbtPluginsDependenciesState: Seq[SbtPluginDependencyState],
                                  otherDependenciesState: Seq[OtherDependencyState],
                                  lastGitUpdateDate:Option[Date])

object RepositoryDependencies {
  implicit val osf = Json.format[OtherDependencyState]
  implicit val ldsf = Json.format[LibraryDependencyState]
  implicit val spdsf = Json.format[SbtPluginDependencyState]
  implicit val format = Json.format[RepositoryDependencies]
}


@Singleton
class ServiceDependenciesController @Inject()(configuration: Configuration,
                                              dependencyDataUpdatingService: DependencyDataUpdatingService,
                                              config: ServiceDependenciesConfig) extends BaseController {

  val logger = LoggerFactory.getLogger(this.getClass)

  private val doneResult = Ok("Done")

  implicit val environmentDependencyWrites = Json.writes[EnvironmentDependency]
  implicit val serviceDependenciesWrites = Json.writes[ServiceDependencies]


  def getDependencyVersionsForRepository(repositoryName: String) = Action.async {
    dependencyDataUpdatingService.getDependencyVersionsForRepository(repositoryName)
      .map(maybeRepositoryDependencies =>
        maybeRepositoryDependencies.fold(
          NotFound(s"$repositoryName not found"))(repoDependencies => Ok(Json.toJson(repoDependencies))))
  }

  def dependencies() = Action.async {
    dependencyDataUpdatingService.getDependencyVersionsForAllRepositories().map(dependencies => Ok(Json.toJson(dependencies)))
  }


  def reloadLibraryDependenciesForAllRepositories() = Action {
    dependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories().map(_ => logger.debug(s"""${">" * 10} done ${"<" * 10}""")).onFailure{
      case ex => throw new RuntimeException("reload of dependencies failed", ex)
    }
    doneResult
  }


  def reloadLibraryVersions() = Action {
    dependencyDataUpdatingService.reloadLatestLibraryVersions().map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure{
      case ex => throw new RuntimeException("reload of libraries failed", ex)
    }
    doneResult
  }

  def reloadSbtPluginVersions() = Action {
    dependencyDataUpdatingService.reloadLatestSbtPluginVersions().map(_ => println(s"""${">" * 10} done ${"<" * 10}""")).onFailure{
      case ex => throw new RuntimeException("reload of sbt plugins failed", ex)
    }
    doneResult
  }


  def libraries() = Action.async {
    dependencyDataUpdatingService.getAllCuratedLibraries().map(versions => Ok(Json.toJson(versions)))
  }


  def sbtPlugins() = Action.async {
    dependencyDataUpdatingService.getAllCuratedSbtPlugins().map(versions => Ok(Json.toJson(versions)))
  }

  def locks() = Action.async {
    dependencyDataUpdatingService.locks().map(locks => Ok(Json.toJson(locks)))
  }

  def dropCollection(collection: String) = Action.async {
    dependencyDataUpdatingService.dropCollection(collection).map(_ => Ok(s"$collection dropped"))
  }

  def clearAllGithubLastUpdateDates = Action.async {
    dependencyDataUpdatingService.clearAllGithubLastUpdateDates.map(rs => Ok(s"${rs.size} records updated"))
  }

}


