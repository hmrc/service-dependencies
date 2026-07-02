/*
 * Copyright 2023 HM Revenue & Customs
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
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.service.{LatestVersionService, MetaArtefactBulkCleanupService}
import uk.gov.hmrc.servicedependencies.service.MetaArtefactBulkCleanupService.BulkCleanupResult

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AdministrationController @Inject()(
    latestVersionService          : LatestVersionService
  , metaArtefactBulkCleanupService: MetaArtefactBulkCleanupService
  , cc                            : ControllerComponents
  )(using
    ec: ExecutionContext
  ) extends BackendController(cc):

  def reloadLatestVersions: Action[AnyContent] =
    Action:
      latestVersionService
        .reloadLatestVersions()
        .recoverWith: ex =>
          Future.failed(RuntimeException("reload of dependency versions failed", ex))
      Accepted("reload started")

  def cleanupMetaArtefactQueue: Action[JsValue] =
    Action.async(parse.json): request =>
      val messageType = (request.body \ "type").asOpt[String]
      val maxMessages = (request.body \ "maxMessages").asOpt[Int].getOrElse(1000)
      val dryRun      = (request.body \ "dryRun").asOpt[Boolean].getOrElse(true)

      messageType match
        case Some("deletion") if maxMessages >= 1 && maxMessages <= 1000 =>
          given play.api.libs.json.Writes[BulkCleanupResult] = BulkCleanupResult.writes
          metaArtefactBulkCleanupService
            .cleanupDeletions(maxMessages, dryRun)
            .map(result => Ok(Json.toJson(result)))

        case Some("deletion") =>
          Future.successful(BadRequest("maxMessages must be between 1 and 1000"))

        case _ =>
          Future.successful(BadRequest("type must be deletion"))
