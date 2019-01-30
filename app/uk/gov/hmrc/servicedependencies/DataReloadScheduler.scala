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

import com.google.inject.Singleton
import play.api._
import play.api.inject.ApplicationLifecycle

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.servicedependencies.service.UpdateScheduler

import scala.concurrent.Future
import javax.inject.Inject
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.SchedulerConfig

@Singleton
class DataReloadScheduler @Inject()(
  configuration: Configuration,
  schedulerConfig: SchedulerConfig,
  updateScheduler: UpdateScheduler,
  applicationLifecycle: ApplicationLifecycle) {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  if(schedulerConfig.dataReloadSchedulerEnabled) {
    scheduleRepositoryDependencyDataReloadSchedule(schedulerConfig.dependenciesReloadIntervalMins)
    scheduleLibraryVersionDataReloadSchedule(schedulerConfig.libraryReloadIntervalMins)
    scheduleSbtPluginVersionDataReloadSchedule(schedulerConfig.sbtReloadIntervalMins)
  }
  else {
    Logger.info("DataReloadScheduler is DISABLED. to enabled, configure scheduler.enabled=false in config.")
  }



  import scala.concurrent.duration._

  private def scheduleRepositoryDependencyDataReloadSchedule(maybeReloadInterval: Option[Int])(implicit hc: HeaderCarrier) = {

    maybeReloadInterval.fold {
      Logger.warn(s"dependency.reload.intervalminutes is missing. repositoryDependencyDataReloadScheduler will be disabled")
    } { reloadInterval =>
      Logger.warn(s"repositoryDependenciesReloadInterval set to $reloadInterval minutes")
      val cancellable = updateScheduler.startUpdatingLibraryDependencyData(reloadInterval minutes)
      applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
    }
  }

  private def scheduleLibraryVersionDataReloadSchedule(maybeReloadInterval: Option[Int])(implicit hc: HeaderCarrier) = {

    maybeReloadInterval.fold {
      Logger.warn(s"library.reload.intervalminutes is missing. LibraryVersionDataReloadScheduler will be disabled")
    } { reloadInterval =>
      Logger.warn(s"libraryReloadIntervalKey set to $reloadInterval minutes")
      val cancellable = updateScheduler.startUpdatingLibraryData(reloadInterval minutes)
      applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
    }
  }

  private def scheduleSbtPluginVersionDataReloadSchedule(maybeReloadInterval: Option[Int])(implicit hc: HeaderCarrier) = {

    maybeReloadInterval.fold {
      Logger.warn(s"sbtPlugin.reload.intervalminutes is missing. SbtPluginVersionDataReloadScheduler will be disabled")
    } { reloadInterval =>
      Logger.warn(s"sbtPluginReloadIntervalKey set to $reloadInterval minutes")
      val cancellable = updateScheduler.startUpdatingSbtPluginVersionData(reloadInterval minutes)
      applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
    }
  }
}
