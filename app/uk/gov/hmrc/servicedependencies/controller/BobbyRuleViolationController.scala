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
import javax.inject.Inject
import play.api.Configuration
import play.api.cache.AsyncCacheApi
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.servicedependencies.model.{BobbyRuleViolation, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.service.DependencyLookupCache

import scala.concurrent.{ExecutionContext, Future}

class BobbyRuleViolationController @Inject() (configuration : Configuration,
                                              slugLookup    : DependencyLookupCache,
                                              cache         : AsyncCacheApi,
                                              cc            : ControllerComponents
                                             ) (implicit ec: ExecutionContext) extends BackendController(cc){


  def findBobbyRuleViolations(env: String): Action[AnyContent] = {
    implicit val brw = BobbyRuleViolation.writes
    Action.async { implicit request =>
      SlugInfoFlag.parse(env) match {
        case None    => Future(BadRequest("invalid environment"))
        case Some(e) => slugLookup.countBobbyRuleViolations(e).map(v => Ok(Json.toJson(v)) )
      }
    }
  }

}
