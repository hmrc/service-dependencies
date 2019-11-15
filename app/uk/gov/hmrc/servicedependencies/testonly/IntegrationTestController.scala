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
import uk.gov.hmrc.mongo.play.PlayMongoCollection
import uk.gov.hmrc.mongo.play.json.MongoJavatimeFormats
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._

import scala.concurrent.{ExecutionContext, Future}

class IntegrationTestController @Inject()(
    libraryRepo               : LibraryVersionRepository
  , sbtPluginVersionRepository: SbtPluginVersionRepository
  , dependenciesRepository    : RepositoryLibraryDependenciesRepository
  , sluginfoRepo              : SlugInfoRepository
  , bobbyRulesSummaryRepo     : BobbyRulesSummaryRepository
  , cc                        : ControllerComponents
  ) extends BackendController(cc) {

  import ExecutionContext.Implicits.global

  implicit val dtf                   = MongoJavatimeFormats.localDateFormats
  implicit val vf                    = Version.apiFormat
  implicit val libraryVersionReads   = Json.using[Json.WithDefaultValues].reads[MongoLibraryVersion]
  implicit val sbtVersionReads       = Json.using[Json.WithDefaultValues].reads[MongoSbtPluginVersion]
  implicit val dependenciesReads     = Json.using[Json.WithDefaultValues].reads[MongoRepositoryDependencies]

  implicit val sluginfoReads         = { implicit val sdr = Json.using[Json.WithDefaultValues].reads[SlugDependency]
                                         implicit val javaInfoReads = Json.using[Json.WithDefaultValues].reads[JavaInfo]
                                         Json.using[Json.WithDefaultValues].reads[SlugInfo]
                                       }
  implicit val bobbyRulesSummaryReads = BobbyRulesSummary.apiFormat

  private def validateJson[A : Reads] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def addLibraryVersion =
    Action.async(validateJson[Seq[MongoLibraryVersion]]) { implicit request =>
      Future.sequence(request.body.map(libraryRepo.update))
        .map(_ => NoContent)
    }

  def addSbtVersions =
    Action.async(validateJson[Seq[MongoSbtPluginVersion]]) { implicit request =>
      Future.sequence(request.body.map(sbtPluginVersionRepository.update))
        .map(_ => NoContent)
    }

  def addDependencies =
    Action.async(validateJson[Seq[MongoRepositoryDependencies]]) { implicit request =>
      Future.sequence(request.body.map(dependenciesRepository.update))
        .map(_ => NoContent)
    }

  def addSluginfos =
    Action.async(validateJson[Seq[SlugInfo]]) { implicit request =>
      Future.sequence(request.body.map(sluginfoRepo.add))
        .map(_ => NoContent)
    }

  def addBobbyRulesSummaries =
    Action.async(validateJson[Seq[BobbyRulesSummary]]) { implicit request =>
      Future.sequence(request.body.map(bobbyRulesSummaryRepo.add))
        .map(_ => NoContent)
    }

  def deleteLibraryVersions =
    Action.async { implicit request =>
      dropCollection(libraryRepo)
        .map(_ => NoContent)
    }

  def deleteSbtVersions =
    Action.async { implicit request =>
      dropCollection(sbtPluginVersionRepository)
        .map(_ => NoContent)
    }

  def deleteDependencies =
    Action.async { implicit request =>
      dropCollection(dependenciesRepository)
        .map(_ => NoContent)
    }

  def deleteSluginfos =
    Action.async { implicit request =>
      dropCollection(sluginfoRepo)
        .map(_ => NoContent)
    }

  def deleteBobbyRulesSummaries =
    Action.async { implicit request =>
      dropCollection(bobbyRulesSummaryRepo)
        .map(_ => NoContent)
    }

  def dropCollection[A](c: PlayMongoCollection[A]) = c.collection.drop().toFuture()

  def deleteAll = {
    val repos = List(sbtPluginVersionRepository, dependenciesRepository,
            libraryRepo, sluginfoRepo, bobbyRulesSummaryRepo)
    
    Action.async { implicit request =>
      Future.sequence(repos.map(r => dropCollection(r))).map(_ => NoContent)
    }
  }

}
