/*
 * Copyright 2017 HM Revenue & Customs
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

import com.kenshoo.play.metrics.MetricsFilter
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import org.slf4j.MDC
import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.mvc._
import uk.gov.hmrc.play.config.{ControllerConfig, RunMode}
import uk.gov.hmrc.play.microservice.filters._
import uk.gov.hmrc.play.graphite.GraphiteConfig
import uk.gov.hmrc.play.microservice.bootstrap.JsonErrorHandling
import uk.gov.hmrc.play.microservice.bootstrap.Routing.RemovingOfTrailingSlashes
import uk.gov.hmrc.servicedependencies.service.UpdateScheduler
import play.api.libs.concurrent.Execution.Implicits.defaultContext


import scala.concurrent.Future
import uk.gov.hmrc.play.microservice.filters.{ LoggingFilter, MicroserviceFilterSupport }

object ControllerConfiguration extends ControllerConfig {
  lazy val controllerConfigs = Play.current.configuration.underlying.as[Config]("controllers")
}

object MicroserviceLoggingFilter extends LoggingFilter with MicroserviceFilterSupport {
  override def controllerNeedsLogging(controllerName: String) = ControllerConfiguration.paramsForController(controllerName).needsLogging
}

object MicroserviceGlobal extends GlobalSettings
  with GraphiteConfig with RemovingOfTrailingSlashes with JsonErrorHandling with RunMode with MicroserviceFilterSupport {

  lazy val appName = "service-dependencies"
  lazy val loggerDateFormat: Option[String] = Play.current.configuration.getString("logger.json.dateformat")

  val repositoryDependenciesReloadIntervalKey = "dependency.reload.intervalminutes"
  val libraryReloadIntervalKey = "library.reload.intervalminutes"
  val sbtPluginReloadIntervalKey = "sbtPlugin.reload.intervalminutes"


  override def onStart(app: Application) {
    Logger.info(s"Starting microservice : $appName : in mode : ${app.mode}")
    MDC.put("appName", appName)
    loggerDateFormat.foreach(str => MDC.put("logger.json.dateformat", str))
    super.onStart(app)

    scheduleRepositoryDependencyDataReloadSchedule(app)
    scheduleLibraryVersionDataReloadSchedule(app)
    scheduleSbtPluginVersionDataReloadSchedule(app)
  }

  import scala.concurrent.duration._

  private def scheduleRepositoryDependencyDataReloadSchedule(app: Application) = {
    val maybeReloadInterval = app.configuration.getInt(repositoryDependenciesReloadIntervalKey)

    maybeReloadInterval.fold {
      Logger.warn(s"$repositoryDependenciesReloadIntervalKey is missing. reload will be disabled")
    } { reloadInterval =>
      Logger.warn(s"repositoryDependenciesReloadInterval set to $reloadInterval minutes")
      val cancellable = UpdateScheduler.startUpdatingLibraryDependencyData(reloadInterval minutes)
      app.injector.instanceOf[ApplicationLifecycle].addStopHook(() => Future(cancellable.cancel()))
    }
  }

  private def scheduleLibraryVersionDataReloadSchedule(app: Application) = {
    val maybeReloadInterval = app.configuration.getInt(libraryReloadIntervalKey)

    maybeReloadInterval.fold {
      Logger.warn(s"$libraryReloadIntervalKey is missing. reload will be disabled")
    } { reloadInterval =>
      Logger.warn(s"libraryReloadIntervalKey set to $reloadInterval minutes")
      val cancellable = UpdateScheduler.startUpdatingLibraryData(reloadInterval minutes)
      app.injector.instanceOf[ApplicationLifecycle].addStopHook(() => Future(cancellable.cancel()))
    }
  }

  private def scheduleSbtPluginVersionDataReloadSchedule(app: Application) = {
    val maybeReloadInterval = app.configuration.getInt(sbtPluginReloadIntervalKey)

    maybeReloadInterval.fold {
      Logger.warn(s"$sbtPluginReloadIntervalKey is missing. reload will be disabled")
    } { reloadInterval =>
      Logger.warn(s"sbtPluginReloadIntervalKey set to $reloadInterval minutes")
      val cancellable = UpdateScheduler.startUpdatingSbtPluingVersionData(reloadInterval minutes)
      app.injector.instanceOf[ApplicationLifecycle].addStopHook(() => Future(cancellable.cancel()))
    }
  }


  protected lazy val microserviceFilters: Seq[EssentialFilter] = Seq(
    Some(Play.current.injector.instanceOf[MetricsFilter]),
    Some(MicroserviceLoggingFilter),
    Some(NoCacheFilter),
    Some(RecoveryFilter)).flatten

  override def doFilter(a: EssentialAction): EssentialAction = {
    Filters(super.doFilter(a), microserviceFilters: _*)
  }

  override def microserviceMetricsConfig(implicit app: Application): Option[Configuration] = app.configuration.getConfig(s"microservice.metrics")
}
