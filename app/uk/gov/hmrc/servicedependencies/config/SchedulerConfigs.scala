/*
 * Copyright 2020 HM Revenue & Customs
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
  , frequency   : () => FiniteDuration
  , initialDelay: () => FiniteDuration
  )

object SchedulerConfig {
  import ConfigUtils._

  def apply(
      configuration: Configuration
    , enabledKey   : String
    , frequency    : => FiniteDuration
    , initialDelay : => FiniteDuration
    ): SchedulerConfig =
      SchedulerConfig(
          enabledKey
        , enabled      = configuration.get[Boolean](enabledKey)
        , frequency    = () => frequency
        , initialDelay = () => initialDelay
        )

  def apply(
      configuration   : Configuration
    , enabledKey      : String
    , frequencyKey    : String
    , initialDelayKey : String
    ): SchedulerConfig =
      SchedulerConfig(
          enabledKey
        , enabled      = configuration.get[Boolean](enabledKey)
        , frequency    = () => getDuration(configuration, frequencyKey)
        , initialDelay = () => getDuration(configuration, initialDelayKey)
        )
}

@Singleton
class SchedulerConfigs @Inject()(configuration: Configuration) extends ConfigUtils {

  val slugMetadataUpdate = SchedulerConfig(
      configuration
    , enabledKey      = "repositoryDependencies.slugJob.enabled"
    , frequencyKey    = "repositoryDependencies.slugJob.interval"
    , initialDelayKey = "repositoryDependencies.slugJob.initialDelay"
    )

  val bobbyRulesSummary = SchedulerConfig(
      configuration
    , enabledKey      = "repositoryDependencies.bobbyRulesSummaryScheduler.enabled"
    , frequencyKey    = "repositoryDependencies.bobbyRulesSummaryScheduler.interval"
    , initialDelayKey = "repositoryDependencies.bobbyRulesSummaryScheduler.initialDelay"
    )

  val metrics = SchedulerConfig(
      configuration
    , enabledKey      = "repositoryDependencies.metricsGauges.enabled"
    , frequencyKey    = "repositoryDependencies.metricsGauges.interval"
    , initialDelayKey = "repositoryDependencies.metricsGauges.initialDelay"
    )

  val dependenciesReload = SchedulerConfig(
      configuration
    , enabledKey   = "scheduler.enabled"
      // TODO move 'minutes' out of key name into value - then reuse getDuration
    , frequency    = configuration.get[Int]("dependency.reload.intervalminutes").minutes
    , initialDelay = 100.milliseconds
    )

  val libraryReload = SchedulerConfig(
      configuration
    , enabledKey   = "scheduler.enabled"
    , frequency    = configuration.get[Int]("library.reload.intervalminutes").minutes
    , initialDelay = 100.milliseconds
    )

  val sbtReload = SchedulerConfig(
      configuration
    , enabledKey   = "scheduler.enabled"
    , frequency    = configuration.get[Int]("sbtPlugin.reload.intervalminutes").minutes
    , initialDelay = 100.milliseconds
    )
}
