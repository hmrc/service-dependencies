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

package uk.gov.hmrc.servicedependencies.controller.admin

import com.google.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.{JsError, Reads}
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.model.{ApiSlugInfoFormats, SlugInfo}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ServiceMetaController @Inject()(cc: ControllerComponents)
                                     (implicit ec: ExecutionContext)
extends BackendController(cc) {

  implicit val slugInfoFormat = ApiSlugInfoFormats.siFormat

  private def validateJson[A: Reads] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def setSlugInfo =
    Action.async(validateJson[SlugInfo]) { implicit request =>
      Future.successful(logSlugInfo(request.body))
        .map(_ => Ok("Done"))
    }

  private def logSlugInfo(slugInfo: SlugInfo) = {
    Logger.info(s"Setting slug data: ${slugInfo.name} ${slugInfo.version.original}")
    true
  }

}
