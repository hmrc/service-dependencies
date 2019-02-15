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

import akka.actor.{ActorSystem, Cancellable}
import com.google.inject.Singleton
import javax.inject.Inject
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.SchedulerConfig
import uk.gov.hmrc.servicedependencies.service.DependencyDataUpdatingService

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

@Singleton
class DataReloadScheduler @Inject()(
  schedulerConfig              : SchedulerConfig,
  actorSystem                  : ActorSystem,
  dependencyDataUpdatingService: DependencyDataUpdatingService,
  applicationLifecycle         : ApplicationLifecycle) {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  import ExecutionContext.Implicits.global


  private val drSchedulerConfig = schedulerConfig.DataReload

  if (drSchedulerConfig.enabled) {
    schedule(
        "libraryDependencyDataReloader"
      , initialDelay = drSchedulerConfig.dependenciesReloadInitialDelay
      , optFrequency = drSchedulerConfig.dependenciesReloadInterval
      ){
        dependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories()
          .map(_ => ())
      }

    schedule(
        "libraryDataReloader"
      , initialDelay = drSchedulerConfig.libraryReloadInitialDelay
      , optFrequency = drSchedulerConfig.libraryReloadInterval
      ){
        dependencyDataUpdatingService.reloadLatestLibraryVersions()
          .map(_ => ())
      }

    schedule(
        "SbtPluginDataReloader"
      , initialDelay = drSchedulerConfig.sbtReloadInitialDelay
      , optFrequency = drSchedulerConfig.sbtReloadInterval
      ) {
        dependencyDataUpdatingService.reloadLatestSbtPluginVersions()
          .map(_ => ())
      }
  } else {
    Logger.info("DataReloadScheduler is DISABLED. to enabled, configure scheduler.enabled=true in config.")
  }

  private def schedule(
      label       : String
    , initialDelay: FiniteDuration
    , optFrequency: Option[FiniteDuration]
    )(f: => Future[Unit]) =
      optFrequency.fold {
        Logger.warn(s"interval config missing for $label - will be disabled")
      } { frequency =>
        Logger.info(s"Initialising $label update every $frequency")
        val cancellable =
          actorSystem.scheduler.schedule(initialDelay, frequency) {
            f.recover {
               case NonFatal(e) => Logger.error(s"$label interrupted because: ${e.getMessage}", e)
             }
          }
        applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
      }
}
