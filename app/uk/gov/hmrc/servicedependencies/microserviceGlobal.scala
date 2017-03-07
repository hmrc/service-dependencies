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
import play.api.mvc._
import uk.gov.hmrc.play.config.{ControllerConfig, RunMode}
import uk.gov.hmrc.play.filters.{MicroserviceFilterSupport, _}
import uk.gov.hmrc.play.graphite.GraphiteConfig
import uk.gov.hmrc.play.http.logging.filters.LoggingFilter
import uk.gov.hmrc.play.microservice.bootstrap.JsonErrorHandling
import uk.gov.hmrc.play.microservice.bootstrap.Routing.RemovingOfTrailingSlashes

object ControllerConfiguration extends ControllerConfig {
  lazy val controllerConfigs = Play.current.configuration.underlying.as[Config]("controllers")
}

object MicroserviceLoggingFilter extends LoggingFilter with MicroserviceFilterSupport {
  override def controllerNeedsLogging(controllerName: String) = ControllerConfiguration.paramsForController(controllerName).needsLogging
}

object MicroserviceGlobal extends GlobalSettings
  with GraphiteConfig with RemovingOfTrailingSlashes with JsonErrorHandling with RunMode with MicroserviceFilterSupport {

  lazy val appName = Play.current.configuration.getString("appName").getOrElse("APP NAME NOT SET")
  lazy val loggerDateFormat: Option[String] = Play.current.configuration.getString("logger.json.dateformat")

  override def onStart(app: Application) {
    Logger.info(s"Starting microservice : $appName : in mode : ${app.mode}")
    MDC.put("appName", appName)
    loggerDateFormat.foreach(str => MDC.put("logger.json.dateformat", str))
    super.onStart(app)
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
