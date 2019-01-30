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
import java.util.concurrent.TimeUnit

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration}

@Singleton
class SchedulerConfig @Inject()(configuration: Configuration) {

  private val schedulerEnabledKey           = "scheduler.enabled"
  private val metricsIntervalKey            = "repositoryDependencies.metricsGauges.interval"
  private val dependenciesReloadIntervalKey = "dependency.reload.intervalminutes"
  private val libraryReloadIntervalKey      = "library.reload.intervalminutes"
  private val sbtPluginReloadIntervalKey    = "sbtPlugin.reload.intervalminutes"
  private val slugJobCreatorIntervalKey     = "repositoryDependencies.slugJob.interval"
  private val slugJobCreatorEnabledKey      = "repositoryDependencies.slugJob.enabled"


  // Config for SlugJobCreatorScheduler
  def slugJobCreatorInterval: FiniteDuration =
    Option(configuration.getMillis(slugJobCreatorIntervalKey))
      .map(_.milliseconds)
      .getOrElse(FiniteDuration(30, TimeUnit.MINUTES))

  def slugJobCreatorEnabled: Boolean =
    configuration.getOptional[Boolean](slugJobCreatorEnabledKey).getOrElse(false)


  // Config for MetricsScheduler
  def metricsInterval: FiniteDuration =
    Option(configuration.getMillis(metricsIntervalKey))
      .map(_.milliseconds)
      .getOrElse(FiniteDuration(10, TimeUnit.MINUTES))


  // Config for DataReloadScheduler
  def dataReloadSchedulerEnabled: Boolean         = configuration.getOptional[Boolean](schedulerEnabledKey).getOrElse(false)
  def dependenciesReloadIntervalMins: Option[Int] = configuration.getOptional[Int](dependenciesReloadIntervalKey)
  def libraryReloadIntervalMins: Option[Int]      = configuration.getOptional[Int](libraryReloadIntervalKey)
  def sbtReloadIntervalMins: Option[Int]          = configuration.getOptional[Int](sbtPluginReloadIntervalKey)

}
