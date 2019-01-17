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
import uk.gov.hmrc.servicedependencies.service.SlugJobUpdater

import scala.concurrent.Future
import scala.concurrent.duration.{DurationLong, FiniteDuration}

class SlugJobCreatorScheduler @Inject()(
    actorSystem             : ActorSystem,
    configuration           : Configuration,
    slugUpdater             : SlugJobUpdater,
    applicationLifecycle    : ApplicationLifecycle) {

  import scala.concurrent.ExecutionContext.Implicits.global

  private val slugJobCreatorEnabledKey  = "repositoryDependencies.slugJobs.enabled"
  private val slugJobCreatorIntervalKey = "repositoryDependencies.slugJobs.interval"
  private val slugJobCreatorLimitKey    = "repositoryDependencies.slugJobs.limit"

  private lazy val slugParseInterval: FiniteDuration =
    Option(configuration.getMillis(slugJobCreatorIntervalKey))
      .map(_.milliseconds)
      .getOrElse(throw new RuntimeException(s"$slugJobCreatorIntervalKey not specified"))

  private lazy val slugUpdaterEnabled : Boolean = configuration.getOptional[Boolean](slugJobCreatorEnabledKey).getOrElse(false)

  private lazy val slugUpdaterLimit: Option[Int] = configuration.getOptional[Int](slugJobCreatorLimitKey)



  if( slugUpdaterEnabled ) {
    val cancellable = actorSystem.scheduler.schedule(1.minute, slugParseInterval) {

      Logger.info(s"Starting slug job creator scheduler, limited to ${slugUpdaterLimit.map(_.toString).getOrElse("unlimited")} items")

      slugUpdaterLimit match {
        case Some(limit) => slugUpdater.update(limit)
        case None        => slugUpdater.update()
      }
    }
    applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
  }
  else {
    Logger.info("Slug job creator is DISABLED. No new slug parser jobs will be created.")
  }
}
