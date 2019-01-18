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
import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.servicedependencies.service.SlugJobCreator

import scala.concurrent.Future
import scala.concurrent.duration.{DurationLong, FiniteDuration}

class SlugJobCreatorScheduler @Inject()(
    actorSystem             : ActorSystem,
    configuration           : Configuration,
    slugJobCreator          : SlugJobCreator,
    applicationLifecycle    : ApplicationLifecycle) {

  import scala.concurrent.ExecutionContext.Implicits.global

  private val enabledKey  = "repositoryDependencies.slugJobCreator.enabled"
  private val intervalKey = "repositoryDependencies.slugJobCreator.interval"
  private val limitKey    = "repositoryDependencies.slugJobCreator.limit"

  private lazy val interval: FiniteDuration =
    Option(configuration.getMillis(intervalKey))
      .map(_.milliseconds)
      .getOrElse(throw new RuntimeException(s"$intervalKey not specified"))

  private lazy val enabled: Boolean =
    configuration.getOptional[Boolean](enabledKey).getOrElse(false)

  private lazy val limit: Option[Int] =
    configuration.getOptional[Int](limitKey)


  if (enabled) {
    val cancellable = actorSystem.scheduler.schedule(1.minute, interval) {
      Logger.info(s"Starting slug job creator scheduler, limited to ${limit.map(_.toString).getOrElse("unlimited")} items")

      limit match {
        case Some(limit) => slugJobCreator.run(to = limit)
        case None        => slugJobCreator.run()
      }
    }
    applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
  } else {
    Logger.info("Slug job creator is DISABLED. No new slug parser jobs will be created.")
  }
}
