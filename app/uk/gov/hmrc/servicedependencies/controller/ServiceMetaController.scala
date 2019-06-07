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
import play.api.libs.json.{JsError, Reads}
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.model.{ApiSlugInfoFormats, SlugInfo}
import uk.gov.hmrc.servicedependencies.service.SlugInfoService

import scala.concurrent.ExecutionContext

@Singleton
class ServiceMetaController @Inject()(slugInfoService: SlugInfoService,
                                      cc: ControllerComponents)
                                     (implicit ec: ExecutionContext)
extends BackendController(cc) {

  private def validateJson[A: Reads] = parse.json.validate(_.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e))))

  def setSlugInfo(): Action[SlugInfo] = {
    implicit val reads: Reads[SlugInfo] = ApiSlugInfoFormats.slugReads
    Action.async(validateJson[SlugInfo]) { implicit request =>
      slugInfoService.addSlugInfo(request.body)
        .map(_ => Ok("Done"))
    }
  }

}