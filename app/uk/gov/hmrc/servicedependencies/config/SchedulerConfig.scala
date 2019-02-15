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

import scala.concurrent.duration.{DurationInt, FiniteDuration}

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
    val enabled                   : Boolean                = configuration.get[Boolean]("scheduler.enabled")
    val dependenciesReloadInterval: Option[FiniteDuration] = configuration.getOptional[Int]("dependency.reload.intervalminutes").map(_.minutes)
    val libraryReloadInterval     : Option[FiniteDuration] = configuration.getOptional[Int]("library.reload.intervalminutes").map(_.minutes)
    val sbtReloadInterval         : Option[FiniteDuration] = configuration.getOptional[Int]("sbtPlugin.reload.intervalminutes").map(_.minutes)
    val dependenciesReloadInitialDelay = 100.milliseconds
    val libraryReloadInitialDelay      = 100.milliseconds
    val sbtReloadInitialDelay          = 100.milliseconds
  }
}
