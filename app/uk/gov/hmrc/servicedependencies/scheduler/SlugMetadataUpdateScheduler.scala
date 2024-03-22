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
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{ScheduledLockService, MongoLockRepository}
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.service.DerivedViewsService
import uk.gov.hmrc.servicedependencies.util.SchedulerUtils

import scala.concurrent.ExecutionContext

class SlugMetadataUpdateScheduler @Inject()(
   schedulerConfigs   : SchedulerConfigs,
   derivedViewsService: DerivedViewsService,
   mongoLockRepository: MongoLockRepository,
   timestampSupport   : TimestampSupport
 )(implicit
   actorSystem         : ActorSystem,
   applicationLifecycle: ApplicationLifecycle,
   ec                  : ExecutionContext
 ) extends SchedulerUtils
   with Logging {

  private val lock: ScheduledLockService =
    ScheduledLockService(
      lockRepository    = mongoLockRepository,
      lockId            = "slug-job-scheduler",
      timestampSupport  = timestampSupport,
      schedulerInterval = schedulerConfigs.slugMetadataUpdate.interval
    )

  implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleWithLock("Slug Metadata Updater", schedulerConfigs.slugMetadataUpdate, lock) {
    logger.info("Updating slug metadata")
    for {
      _ <- derivedViewsService.updateDeploymentData()
      _ =  logger.info("Finished updating deployment data")
      _ <- derivedViewsService.updateDerivedViews()
      _ =  logger.info("Finished updating derived views")
    } yield ()
  }
}
