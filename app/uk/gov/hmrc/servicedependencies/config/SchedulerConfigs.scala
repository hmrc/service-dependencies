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

case class SchedulerConfig(
    enabledKey  : String
  , enabled     : Boolean
  , frequency   : FiniteDuration
  , initialDelay: FiniteDuration
  )

@Singleton
class SchedulerConfigs @Inject()(configuration: Configuration) extends ConfigUtils {

  val slugJobCreator = SchedulerConfig(
      enabledKey   = "repositoryDependencies.slugJob.enabled"
    , enabled      = configuration.get[Boolean]("repositoryDependencies.slugJob.enabled")
    , frequency    = getDuration(configuration, "repositoryDependencies.slugJob.interval")
    , initialDelay = getDuration(configuration, "repositoryDependencies.slugJob.initialDelay")
    )

  val metrics = SchedulerConfig(
      enabledKey   = "repositoryDependencies.metricsGauges.enabled"
    , enabled      = configuration.get[Boolean]("repositoryDependencies.metricsGauges.enabled")
    , frequency    = getDuration(configuration, "repositoryDependencies.metricsGauges.interval")
    , initialDelay = getDuration(configuration, "repositoryDependencies.metricsGauges.initialDelay")
    )

  val dependenciesReload = SchedulerConfig(
      enabledKey   = "scheduler.enabled"
    , enabled      = configuration.get[Boolean]("scheduler.enabled")
      // TODO move 'minutes' out of key name into value - then reuse getDuration
    , frequency    = configuration.get[Int]("dependency.reload.intervalminutes").minutes
    , initialDelay = 100.milliseconds
    )

  val libraryReload = SchedulerConfig(
      enabledKey   = "scheduler.enabled"
    , enabled      = configuration.get[Boolean]("scheduler.enabled")
    , frequency    = configuration.get[Int]("library.reload.intervalminutes").minutes
    , initialDelay = 100.milliseconds
    )

  val sbtReload = SchedulerConfig(
      enabledKey   = "scheduler.enabled"
    , enabled      = configuration.get[Boolean]("scheduler.enabled")
    , frequency    = configuration.get[Int]("sbtPlugin.reload.intervalminutes").minutes
    , initialDelay = 100.milliseconds
    )
}
