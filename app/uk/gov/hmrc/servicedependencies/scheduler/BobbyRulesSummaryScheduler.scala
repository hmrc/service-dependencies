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

package uk.gov.hmrc.servicedependencies.scheduler

import akka.actor.ActorSystem
import javax.inject.Inject
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.persistence.MongoLocks
import uk.gov.hmrc.servicedependencies.service.SlugInfoService
import uk.gov.hmrc.servicedependencies.util.SchedulerUtils
import uk.gov.hmrc.servicedependencies.service.DependencyLookupService

import scala.concurrent.ExecutionContext


class BobbyRulesSummaryScheduler @Inject()(
    schedulerConfigs       : SchedulerConfigs,
    dependencyLookupService: DependencyLookupService,
    mongoLocks             : MongoLocks
  )(implicit
    actorSystem         : ActorSystem,
    applicationLifecycle: ApplicationLifecycle,
    ec                  : ExecutionContext
  ) extends SchedulerUtils {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleWithLock("Bobby Rules Summary", schedulerConfigs.bobbyRulesSummary, mongoLocks.bobbyRulesSummarySchedulerLock) {

    Logger.info("Updating bobby rules summary")
    for {
      _ <- dependencyLookupService.updateBobbyRulesSummary
      _ =  Logger.info("Finished updating bobby rules summary")
    } yield ()
  }
}