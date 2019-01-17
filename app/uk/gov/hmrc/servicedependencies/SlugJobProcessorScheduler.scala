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
import play.api.inject.ApplicationLifecycle
import play.api.Logger
import javax.inject.Inject
import org.joda.time.Duration
import play.api.{Configuration, Logger}
import uk.gov.hmrc.servicedependencies.service.SlugJobProcessor
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.control.NonFatal

class SlugJobProcessorScheduler @Inject()(
  actorSystem         : ActorSystem,
  configuration       : Configuration,
  slugJobProcessor    : SlugJobProcessor,
  applicationLifecycle: ApplicationLifecycle) {

  import ExecutionContext.Implicits.global

  private val enabledKey  = "repositoryDependencies.slugJobProcessor.enabled"
  private val intervalKey = "repositoryDependencies.slugJobProcessor.interval"

  lazy val enabled: Boolean =
    configuration.getOptional[Boolean](enabledKey).getOrElse(false)

  if (enabled) {
    val interval: FiniteDuration =
      Option(configuration.getMillis(intervalKey))
        .map(_.milliseconds)
        .getOrElse(throw new RuntimeException(s"$intervalKey not specified"))

    val cancellable = actorSystem.scheduler.schedule(1.minute, interval) {
      Logger.info("Running slug parser jobs")
      slugJobProcessor.run()
        .recover {
          case NonFatal(e) => Logger.error(s"An error occurred running slug job processor: ${e.getMessage}", e)
        }
    }
    applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
  } else {
    Logger.info("Slug job processor scheduler is DISABLED. No new slug parser jobs will be processed.")
  }
}
