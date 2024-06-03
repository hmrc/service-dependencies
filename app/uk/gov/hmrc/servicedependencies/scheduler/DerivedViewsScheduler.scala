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
import play.api.{Configuration, Logging}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{ScheduledLockService, MongoLockRepository}
import uk.gov.hmrc.servicedependencies.service.DerivedViewsService

import scala.concurrent.ExecutionContext

class DerivedViewsScheduler @Inject()(
   configuration       : Configuration,
   derivedViewsService : DerivedViewsService,
   mongoLockRepository : MongoLockRepository,
   timestampSupport    : TimestampSupport
 )(implicit
   actorSystem         : ActorSystem,
   applicationLifecycle: ApplicationLifecycle,
   ec                  : ExecutionContext
 ) extends SchedulerUtils
   with Logging {

  private val schedulerConfigs =
    SchedulerConfig(configuration, "scheduler.derivedViews")

  private val lock: ScheduledLockService =
    ScheduledLockService(
      lockRepository    = mongoLockRepository
    , lockId            = "derived-views-scheduler"
    , timestampSupport  = timestampSupport
    , schedulerInterval = schedulerConfigs.interval
    )

  implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleWithLock("Derived Views", schedulerConfigs, lock) {
    logger.info("Updating Derived Views & Deployment data")
    for {
      // _ <- derivedViewsService.updateDeploymentDataForAllServices()
      // _ =  logger.info("Finished updating deployment data")
      _ <- derivedViewsService.updateDerivedViewsForAllRepos()
      _ =  logger.info("Finished updating derived views")
    } yield ()
  }
}
