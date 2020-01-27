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

package uk.gov.hmrc.servicedependencies.scheduler

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.throttle.ThrottleConfig
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.metrix.{MetricOrchestrator, MongoMetricRepository}
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.service.RepositoryDependenciesSource
import uk.gov.hmrc.servicedependencies.util.SchedulerUtils

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class MetricsScheduler @Inject()(
    schedulerConfigs            : SchedulerConfigs,
    metrics                     : Metrics,
    mongoComponent              : MongoComponent,
    repositoryDependenciesSource: RepositoryDependenciesSource,
    mongoLockRepository         : MongoLockRepository,
    throttleConfig              : ThrottleConfig
  )(implicit
    actorSystem         : ActorSystem,
    applicationLifecycle: ApplicationLifecycle,
    ec                  : ExecutionContext
  ) extends SchedulerUtils {

  private val schedulerConfig = schedulerConfigs.metrics

  val lock =
    mongoLockRepository.toService("repositoryDependenciesLock", schedulerConfig.frequency())

  val metricOrchestrator = new MetricOrchestrator(
    metricSources    = List(repositoryDependenciesSource),
    lockService      = lock,
    metricRepository = new MongoMetricRepository(mongoComponent, throttleConfig),
    metricRegistry   = metrics.defaultRegistry
  )

  schedule("Metrics", schedulerConfig) {
    metricOrchestrator
      .attemptMetricRefresh(resetToZeroFor = Some(_ => true))
      .map(_.andLogTheResult())
  }
}
