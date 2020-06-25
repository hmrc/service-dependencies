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
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._
import uk.gov.hmrc.servicedependencies.service.DerivedViewsService

import scala.concurrent.{ExecutionContext, Future}

class IntegrationTestController @Inject()(
    latestVersionRepository         : LatestVersionRepository
  , repositoryDependenciesRepository: RepositoryDependenciesRepository
  , sluginfoRepo                    : SlugInfoRepository
  , bobbyRulesSummaryRepo           : BobbyRulesSummaryRepository
  , derivedViewsService             : DerivedViewsService
  , cc                              : ControllerComponents
  )(implicit ec: ExecutionContext
  ) extends BackendController(cc) {

  implicit val dtf                = MongoJavatimeFormats.localDateFormats
  implicit val vf                 = Version.apiFormat
  implicit val latestVersionReads = Json.using[Json.WithDefaultValues].reads[MongoLatestVersion]
  implicit val dependenciesReads  = Json.using[Json.WithDefaultValues].reads[MongoRepositoryDependencies]
  implicit val sluginfoReads      = { implicit val sdr = Json.using[Json.WithDefaultValues].reads[SlugDependency]
                                      implicit val jir = Json.using[Json.WithDefaultValues].reads[JavaInfo]
                                      Json.using[Json.WithDefaultValues].reads[SlugInfo]
                                    }
  implicit val bobbyRulesSummaryReads = BobbyRulesSummary.apiFormat

  private def validateJson[A : Reads] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def addLatestVersions =
    Action.async(validateJson[Seq[MongoLatestVersion]]) { implicit request =>
      Future.sequence(request.body.map(latestVersionRepository.update))
        .map(_ => NoContent)
    }

  def addDependencies =
    Action.async(validateJson[Seq[MongoRepositoryDependencies]]) { implicit request =>
      Future.sequence(request.body.map(repositoryDependenciesRepository.update))
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

  def deleteLatestVersions =
    Action.async {
      latestVersionRepository.clearAllData
        .map(_ => NoContent)
    }

  def deleteDependencies =
    Action.async {
      repositoryDependenciesRepository.clearAllData
        .map(_ => NoContent)
    }

  def deleteSluginfos =
    Action.async {
      sluginfoRepo.clearAllData
        .map(_ => NoContent)
    }

  def deleteBobbyRulesSummaries =
    Action.async {
      bobbyRulesSummaryRepo.clearAllData
        .map(_ => NoContent)
    }

  def deleteAll =
    Action.async {
      Future.sequence(List(
          latestVersionRepository.clearAllData
        , repositoryDependenciesRepository.clearAllData
        , sluginfoRepo.clearAllData
        , bobbyRulesSummaryRepo.clearAllData
        )).map(_ => NoContent)
    }

  def createDerivedViews =
    Action.async {
      derivedViewsService.generateAllViews().map(_ => NoContent)
    }
}
