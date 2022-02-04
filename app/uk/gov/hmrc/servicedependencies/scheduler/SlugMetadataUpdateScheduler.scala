/*
 * Copyright 2022 HM Revenue & Customs
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
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, LockService}
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.service.{DerivedViewsService, SlugInfoService}
import uk.gov.hmrc.servicedependencies.util.SchedulerUtils

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class SlugMetadataUpdateScheduler @Inject()(
   schedulerConfigs          : SchedulerConfigs,
   slugInfoService           : SlugInfoService,
   derivedViewsService       : DerivedViewsService,
   mongoLockRepository       : MongoLockRepository
 )(implicit
   actorSystem               : ActorSystem,
   applicationLifecycle      : ApplicationLifecycle,
   ec                        : ExecutionContext
 ) extends SchedulerUtils
   with Logging {

  private val lock =
    LockService(mongoLockRepository, "slug-job-scheduler", 1.hour)

  implicit val hc: HeaderCarrier = HeaderCarrier()

  // create derived views if the scheduler is disabled, i.e. local dev etc
  if (!schedulerConfigs.slugMetadataUpdate.enabled) {
    logger.info("Pre-populating derived views...")
    derivedViewsService.generateAllViews()
  }

  scheduleWithLock("Slug Metadata Updater", schedulerConfigs.slugMetadataUpdate, lock) {
    logger.info("Updating slug metadata")
    for {
      _ <- slugInfoService.updateMetadata()
      _ =  logger.info("Finished updating slug metadata")
      _ <- derivedViewsService.generateAllViews()
      _ =  logger.info("Finished updating derived views")
    } yield ()
  }
}
