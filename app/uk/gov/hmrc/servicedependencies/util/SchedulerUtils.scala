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

package uk.gov.hmrc.servicedependencies.util

import akka.actor.ActorSystem
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.servicedependencies.config.SchedulerConfig
import uk.gov.hmrc.servicedependencies.persistence.MongoLock

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal


trait SchedulerUtils {
  def schedule(
      label          : String
    , schedulerConfig: SchedulerConfig
    )(f: => Future[Unit]
    )(implicit actorSystem: ActorSystem, applicationLifecycle: ApplicationLifecycle, ec: ExecutionContext) =
      if (schedulerConfig.enabled) {
        val initialDelay = schedulerConfig.initialDelay()
        val frequency    = schedulerConfig.frequency()
        Logger.info(s"Enabling $label scheduler, running every $frequency")
        val cancellable =
          actorSystem.scheduler.schedule(initialDelay, frequency) {
            Logger.info(s"Running $label scheduler")
            f.recover {
                case NonFatal(e) => Logger.error(s"$label interrupted because: ${e.getMessage}", e)
              }
          }
        applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
      } else {
        Logger.info(s"$label scheduler is DISABLED. to enable, configure configure ${schedulerConfig.enabledKey}=true in config.")
      }

  def scheduleWithLock(
      label          : String
    , schedulerConfig: SchedulerConfig
    , lock           : MongoLock
    )(f: => Future[Unit]
    )(implicit actorSystem: ActorSystem, applicationLifecycle: ApplicationLifecycle, ec: ExecutionContext) =
      schedule(label, schedulerConfig) {
        lock.tryLock(f).map {
          case Some(_) => Logger.debug(s"$label finished - releasing lock")
          case None    => Logger.debug(s"$label cannot run - lock ${lock.lockId} is taken... skipping update")
        }
  }
}

object SchedulerUtils extends SchedulerUtils