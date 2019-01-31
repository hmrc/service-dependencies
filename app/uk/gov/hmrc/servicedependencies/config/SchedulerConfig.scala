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

package uk.gov.hmrc.servicedependencies.config

import javax.inject.{Inject, Singleton}
import play.api.Configuration

import scala.concurrent.duration.FiniteDuration

@Singleton
class SchedulerConfig @Inject()(configuration: Configuration) extends ConfigUtils {

  object SlugJobCreator {
    val enabled     : Boolean        = configuration.get[Boolean]("repositoryDependencies.slugJob.enabled")
    val interval    : FiniteDuration = getDuration(configuration, "repositoryDependencies.slugJob.interval")
    val initialDelay: FiniteDuration = getDuration(configuration, "repositoryDependencies.slugJob.initialDelay")
  }

  object Metrics {
    val enabled     : Boolean        = configuration.get[Boolean]("repositoryDependencies.metricsGauges.enabled")
    val interval    : FiniteDuration = getDuration(configuration, "repositoryDependencies.metricsGauges.interval")
    val initialDelay: FiniteDuration = getDuration(configuration, "repositoryDependencies.metricsGauges.initialDelay")
  }

  object DataReload {
    val enabled                       : Boolean     = configuration.get[Boolean]("scheduler.enabled")
    val dependenciesReloadIntervalMins: Option[Int] = configuration.getOptional[Int]("dependency.reload.intervalminutes")
    val libraryReloadIntervalMins     : Option[Int] = configuration.getOptional[Int]("library.reload.intervalminutes")
    val sbtReloadIntervalMins         : Option[Int] = configuration.getOptional[Int]("sbtPlugin.reload.intervalminutes")
  }
}
