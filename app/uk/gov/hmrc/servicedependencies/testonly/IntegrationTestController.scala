/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._

import scala.concurrent.{ExecutionContext, Future}

class IntegrationTestController @Inject()(
    libraryVersionRepository               : LibraryVersionRepository
  , sbtPluginVersionRepository             : SbtPluginVersionRepository
  , repositoryLibraryDependenciesRepository: RepositoryLibraryDependenciesRepository
  , sluginfoRepo                           : SlugInfoRepository
  , bobbyRulesSummaryRepo                  : BobbyRulesSummaryRepository
  , cc                                     : ControllerComponents
  )(implicit ec: ExecutionContext
  ) extends BackendController(cc) {

  implicit val dtf                    = MongoJavatimeFormats.localDateFormats
  implicit val vf                     = Version.apiFormat
  implicit val dependencyVersionReads = Json.using[Json.WithDefaultValues].reads[MongoDependencyVersion]
  implicit val dependenciesReads      = Json.using[Json.WithDefaultValues].reads[MongoRepositoryDependencies]

  implicit val sluginfoReads          = { implicit val sdr = Json.using[Json.WithDefaultValues].reads[SlugDependency]
                                          implicit val javaInfoReads = Json.using[Json.WithDefaultValues].reads[JavaInfo]
                                          Json.using[Json.WithDefaultValues].reads[SlugInfo]
                                        }
  implicit val bobbyRulesSummaryReads = BobbyRulesSummary.apiFormat

  private def validateJson[A : Reads] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def addLibraryVersion =
    Action.async(validateJson[Seq[MongoDependencyVersion]]) { implicit request =>
      Future.sequence(request.body.map(libraryVersionRepository.update))
        .map(_ => NoContent)
    }

  def addSbtVersions =
    Action.async(validateJson[Seq[MongoDependencyVersion]]) { implicit request =>
      Future.sequence(request.body.map(sbtPluginVersionRepository.update))
        .map(_ => NoContent)
    }

  def addDependencies =
    Action.async(validateJson[Seq[MongoRepositoryDependencies]]) { implicit request =>
      Future.sequence(request.body.map(repositoryLibraryDependenciesRepository.update))
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
      libraryVersionRepository.clearAllData
        .map(_ => NoContent)
    }

  def deleteSbtVersions =
    Action.async { implicit request =>
      sbtPluginVersionRepository.clearAllData
        .map(_ => NoContent)
    }

  def deleteDependencies =
    Action.async { implicit request =>
      repositoryLibraryDependenciesRepository.clearAllData
        .map(_ => NoContent)
    }

  def deleteSluginfos =
    Action.async { implicit request =>
      sluginfoRepo.clearAllData
        .map(_ => NoContent)
    }

  def deleteBobbyRulesSummaries =
    Action.async { implicit request =>
      bobbyRulesSummaryRepo.clearAllData
        .map(_ => NoContent)
    }

  def deleteAll =
    Action.async { implicit request =>
      Future.sequence(List(
          sbtPluginVersionRepository.clearAllData
        , repositoryLibraryDependenciesRepository.clearAllData
        , libraryVersionRepository.clearAllData
        , sluginfoRepo.clearAllData
        , bobbyRulesSummaryRepo.clearAllData
        )).map(_ => NoContent)
    }
}
