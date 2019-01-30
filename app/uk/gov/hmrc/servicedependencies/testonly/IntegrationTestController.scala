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

package uk.gov.hmrc.servicedependencies.testonly
import javax.inject.Inject
import play.api.libs.json._
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, MongoRepositoryDependencies, MongoSbtPluginVersion, Version}
import uk.gov.hmrc.servicedependencies.persistence.{LibraryVersionRepository, RepositoryLibraryDependenciesRepository, SbtPluginVersionRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class IntegrationTestController @Inject() (libraryRepo:                LibraryVersionRepository,
                                           sbtPluginVersionRepository: SbtPluginVersionRepository,
                                           dependenciesRepository:     RepositoryLibraryDependenciesRepository,
                                           cc: ControllerComponents) extends BackendController(cc) {



  implicit val dtf                  = ReactiveMongoFormats.dateTimeFormats
  implicit val vf                   = Version.apiFormat
  implicit val libraryVersionFormat = Json.using[Json.WithDefaultValues].format[MongoLibraryVersion]
  implicit val sbtVersionFormat     = Json.using[Json.WithDefaultValues].format[MongoSbtPluginVersion]
  implicit val dependenciesFormat   = Json.using[Json.WithDefaultValues].format[MongoRepositoryDependencies]

  private def validateJson[A : Reads] = parse.json.validate(
    _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
  )


  def addLibraryVersion = Action.async(validateJson[Seq[MongoLibraryVersion]]) { implicit request =>
    Future.sequence(request.body.map(libraryRepo.update)).map(_ => Ok("Done"))
  }


  def addSbtVersion = Action.async(validateJson[Seq[MongoSbtPluginVersion]]) { implicit request =>
    Future.sequence(request.body.map(sbtPluginVersionRepository.update)).map(_ => Ok("Done"))
  }


  def addDependencies = Action.async(validateJson[Seq[MongoRepositoryDependencies]]) { implicit request =>
    Future.sequence(request.body.map(dependenciesRepository.update)).map(_ => Ok("Done"))
  }


  def deleteSbt = Action.async { implicit request =>
      sbtPluginVersionRepository.clearAllData.map(_ => Ok("Done"))
  }


  def deleteVersions = Action.async { implicit request =>
    libraryRepo.clearAllData.map(_ => Ok("Done"))
  }


  def deleteDependencies = Action.async { implicit request =>
    dependenciesRepository.clearAllData.map(_ => Ok("Done"))
  }


  def deleteAll = Action.async { implicit request =>

    val result = for {
      _ <- sbtPluginVersionRepository.clearAllData
      _ <- dependenciesRepository.clearAllData
      f <- libraryRepo.clearAllData
    } yield f

    result.map(_ => Ok("Deleted"))
  }
}
