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

import akka.actor.ActorSystem
import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import org.joda.time.Duration
import play.api.inject.ApplicationLifecycle
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.DefaultDB
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockRepository}
import uk.gov.hmrc.metrix.MetricOrchestrator
import uk.gov.hmrc.metrix.persistence.MongoMetricRepository
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.service.RepositoryDependenciesSource
import uk.gov.hmrc.servicedependencies.util.SchedulerUtils

import scala.concurrent.ExecutionContext


class MetricsScheduler @Inject()(
    schedulerConfigs            : SchedulerConfigs,
    metrics                     : Metrics,
    reactiveMongoComponent      : ReactiveMongoComponent,
    repositoryDependenciesSource: RepositoryDependenciesSource)(
    implicit actorSystem         : ActorSystem,
             applicationLifecycle: ApplicationLifecycle
  ) extends SchedulerUtils {

  import ExecutionContext.Implicits.global

  private val schedulerConfig = schedulerConfigs.metrics

  implicit lazy val mongo: () => DefaultDB = reactiveMongoComponent.mongoConnector.db

  val lock = new ExclusiveTimePeriodLock {
    override def repo: LockRepository  = new LockRepository()
    override def lockId: String        = "repositoryDependenciesLock"
    override def holdLockFor: Duration = new org.joda.time.Duration(schedulerConfig.frequency().toMillis)
  }

  val metricOrchestrator = new MetricOrchestrator(
    metricSources    = List(repositoryDependenciesSource),
    lock             = lock,
    metricRepository = new MongoMetricRepository(),
    metricRegistry   = metrics.defaultRegistry
  )

  schedule("Metrics", schedulerConfig) {
    metricOrchestrator
      .attemptToUpdateRefreshAndResetMetrics(_ => true)
      .map(_.andLogTheResult())
  }
}
