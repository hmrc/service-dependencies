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

package uk.gov.hmrc.servicedependencies.scheduler

import javax.inject.Inject
import org.apache.pekko.actor.ActorSystem
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, LockService}
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.util.SchedulerUtils
import uk.gov.hmrc.servicedependencies.service.DependencyLookupService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class BobbyRulesSummaryScheduler @Inject()(
    schedulerConfigs       : SchedulerConfigs,
    dependencyLookupService: DependencyLookupService,
    mongoLockRepository    : MongoLockRepository
  )(implicit
    actorSystem         : ActorSystem,
    applicationLifecycle: ApplicationLifecycle,
    ec                  : ExecutionContext
  ) extends SchedulerUtils
    with Logging {

  private val lock =
    LockService(mongoLockRepository, "bobby-rules-summary-scheduler", 1.hour)

  implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleWithLock("Bobby Rules Summary", schedulerConfigs.bobbyRulesSummary, lock) {

    logger.info("Updating bobby rules summary")
    for {
      _ <- dependencyLookupService.updateBobbyRulesSummary()
      _ =  logger.info("Finished updating bobby rules summary")
    } yield ()
  }
}
