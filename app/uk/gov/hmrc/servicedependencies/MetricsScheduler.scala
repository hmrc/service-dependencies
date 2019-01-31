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
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.DefaultDB
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockRepository}
import uk.gov.hmrc.metrix.MetricOrchestrator
import uk.gov.hmrc.metrix.persistence.MongoMetricRepository
import uk.gov.hmrc.servicedependencies.config.SchedulerConfig
import uk.gov.hmrc.servicedependencies.service.RepositoryDependenciesSource

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

class MetricsScheduler @Inject()(
    actorSystem                 : ActorSystem,
    schedulerConfig             : SchedulerConfig,
    metrics                     : Metrics,
    reactiveMongoComponent      : ReactiveMongoComponent,
    repositoryDependenciesSource: RepositoryDependenciesSource) {

  import ExecutionContext.Implicits.global

  private val mSchedulerConfig = schedulerConfig.Metrics

  implicit lazy val mongo: () => DefaultDB = reactiveMongoComponent.mongoConnector.db

  val lock = new ExclusiveTimePeriodLock {
    override def repo: LockRepository  = new LockRepository()
    override def lockId: String        = "repositoryDependenciesLock"
    override def holdLockFor: Duration = new org.joda.time.Duration(mSchedulerConfig.interval.toMillis)
  }

  val metricOrchestrator = new MetricOrchestrator(
    metricSources    = List(repositoryDependenciesSource),
    lock             = lock,
    metricRepository = new MongoMetricRepository(),
    metricRegistry   = metrics.defaultRegistry
  )

  if (mSchedulerConfig.enabled) {
    Logger.info(s"Enabling Metrics Scheduler, running every ${mSchedulerConfig.interval}")
    actorSystem.scheduler.schedule(mSchedulerConfig.initialDelay, mSchedulerConfig.interval) {
      metricOrchestrator
        .attemptToUpdateRefreshAndResetMetrics(_ => true)
        .map(_.andLogTheResult())
        .recover {
          case NonFatal(e) => Logger.error(s"An error occurred processing metrics: ${e.getMessage}", e)
        }
    }
  }
  else {
    Logger.info("Metrics Scheduler is DISABLED")
  }
}
