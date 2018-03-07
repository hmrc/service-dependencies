/*
 * Copyright 2018 HM Revenue & Customs
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
import org.slf4j.MDC
import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import uk.gov.hmrc.servicedependencies.service.UpdateScheduler

import scala.concurrent.Future
import javax.inject.Inject

import uk.gov.hmrc.http.HeaderCarrier

@Singleton
class DataReloadScheduler @Inject()(app: Application, configuration: Configuration, updateScheduler: UpdateScheduler, applicationLifecycle: ApplicationLifecycle) {
  lazy val appName = "service-dependencies"
  lazy val loggerDateFormat: Option[String] = configuration.getString("logger.json.dateformat")

  val repositoryDependenciesReloadIntervalKey = "dependency.reload.intervalminutes"
  val libraryReloadIntervalKey = "library.reload.intervalminutes"
  val sbtPluginReloadIntervalKey = "sbtPlugin.reload.intervalminutes"

  Logger.info(s"Starting microservice : $appName : in mode : ${app.mode}")

  implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleRepositoryDependencyDataReloadSchedule(app)
  scheduleLibraryVersionDataReloadSchedule(app)
  scheduleSbtPluginVersionDataReloadSchedule(app)

  import scala.concurrent.duration._

  private def scheduleRepositoryDependencyDataReloadSchedule(app: Application)(implicit hc: HeaderCarrier) = {
    val maybeReloadInterval = app.configuration.getInt(repositoryDependenciesReloadIntervalKey)

    maybeReloadInterval.fold {
      Logger.warn(s"$repositoryDependenciesReloadIntervalKey is missing. reload will be disabled")
    } { reloadInterval =>
      Logger.warn(s"repositoryDependenciesReloadInterval set to $reloadInterval minutes")
      val cancellable = updateScheduler.startUpdatingLibraryDependencyData(reloadInterval minutes)
      applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
    }
  }

  private def scheduleLibraryVersionDataReloadSchedule(app: Application)(implicit hc: HeaderCarrier) = {
    val maybeReloadInterval = app.configuration.getInt(libraryReloadIntervalKey)

    maybeReloadInterval.fold {
      Logger.warn(s"$libraryReloadIntervalKey is missing. reload will be disabled")
    } { reloadInterval =>
      Logger.warn(s"libraryReloadIntervalKey set to $reloadInterval minutes")
      val cancellable = updateScheduler.startUpdatingLibraryData(reloadInterval minutes)
      applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
    }
  }

  private def scheduleSbtPluginVersionDataReloadSchedule(app: Application)(implicit hc: HeaderCarrier) = {
    val maybeReloadInterval = app.configuration.getInt(sbtPluginReloadIntervalKey)

    maybeReloadInterval.fold {
      Logger.warn(s"$sbtPluginReloadIntervalKey is missing. reload will be disabled")
    } { reloadInterval =>
      Logger.warn(s"sbtPluginReloadIntervalKey set to $reloadInterval minutes")
      val cancellable = updateScheduler.startUpdatingSbtPluingVersionData(reloadInterval minutes)
      applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
    }
  }
}
