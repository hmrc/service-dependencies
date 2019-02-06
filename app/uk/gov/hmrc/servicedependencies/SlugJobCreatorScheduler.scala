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

package uk.gov.hmrc.servicedependencies

import akka.actor.ActorSystem
import javax.inject.Inject
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.servicedependencies.config.SchedulerConfig
import uk.gov.hmrc.servicedependencies.service.{SlugJobCreator, SlugJobProcessor}
import uk.gov.hmrc.servicedependencies.persistence.MongoLocks

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class SlugJobCreatorScheduler @Inject()(
    actorSystem         : ActorSystem,
    schedulerConfig     : SchedulerConfig,
    slugJobCreator      : SlugJobCreator,
    slugJobProcessor    : SlugJobProcessor,
    mongoLocks          : MongoLocks,
    applicationLifecycle: ApplicationLifecycle) {

  import ExecutionContext.Implicits.global

  private val slSchedulerConfig = schedulerConfig.SlugJobCreator

  if (slSchedulerConfig.enabled) {
    Logger.info(s"Slug Job Creator is ENABLED, checking for new slugs every ${slSchedulerConfig.interval}.")
    val cancellable = actorSystem.scheduler.schedule(slSchedulerConfig.initialDelay, slSchedulerConfig.interval) {
      Logger.info(s"Starting slug job creator scheduler")

      mongoLocks.slugJobSchedulerLock.tryLock {
        slugJobCreator
          .run
          .flatMap { _ =>
            Logger.info("Finished creating slug jobs - now processing jobs")
            slugJobProcessor.run()
          }
      }.map {
        case Some(_) => Logger.debug(s"Slug job creator finished - releasing lock")
        case None    => Logger.debug(s"Slug job lock is taken... skipping update")
      }.recover {
        case NonFatal(ex) => Logger.error(s"Slug job scheduler failed: ${ex.getMessage}", ex)
      }
    }
    applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
  } else {
    Logger.info("Slug Job Creator is DISABLED. No new slug parser jobs will be created.")
  }
}
