/*
 * Copyright 2025 HM Revenue & Customs
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

import org.apache.pekko.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, ScheduledLockService}
import uk.gov.hmrc.servicedependencies.persistence.ProductionNotificationRepository
import uk.gov.hmrc.servicedependencies.service.{ProductionBobbyNotificationService, ProductionVulnerabilitiesNotificationService}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.Duration
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.HeaderCarrier
import play.api.Configuration

@Singleton
class ProductionNotificationScheduler @Inject() (
  configuration                     : Configuration,
  mongoLockRepository               : MongoLockRepository,
  timestampSupport                  : TimestampSupport,
  bobbyNotificationService          : ProductionBobbyNotificationService,
  vulnerabilitiesNotificationService: ProductionVulnerabilitiesNotificationService,
  productionNotificationRepository  : ProductionNotificationRepository
)(using
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils:

  private given HeaderCarrier = HeaderCarrier()

  private val schedulerConfigs =
    SchedulerConfig(configuration, "scheduler.productionNotification")

  private val cooldown = configuration.get[Duration]("production-notification-scheduler.cooldown")

  scheduleWithLock(
    label = "Production Notification",
    schedulerConfig = schedulerConfigs,
    lock = ScheduledLockService(mongoLockRepository, "production-notification-scheduler", timestampSupport, schedulerConfigs.interval)
  ):
    val now = Instant.now()
    run(now):
      for 
        _ <- bobbyNotificationService.notifyBobbyErrorsInProduction()
        _ <- vulnerabilitiesNotificationService.notifyProductionVulnerabilities()
        _ <- productionNotificationRepository.setLastRunTime(now)
      yield ()


  private def run(now: Instant)(f: => Future[Unit]): Future[Unit] =
    import uk.gov.hmrc.servicedependencies.util.DateAndTimeOps._
    if isInWorkingHours(now) then
      productionNotificationRepository
        .getLastRunTime()
        .flatMap:
          case Some(lrd) if lrd.isAfter(now.truncatedTo(ChronoUnit.DAYS).minus(cooldown.toDays, ChronoUnit.DAYS)) =>
            logger.info(s"Not running Production Notification Scheduler. On cooldown from last run date: $lrd")
            Future.unit
          case optLrd =>
            logger.info(optLrd.fold(s"Running Production Notification Scheduler for the first time")(d => s"Running Production Notification Scheduler. Last run date was $d"))
            f
    else
      logger.info(s"Not running Production Notification Scheduler. Out of hours")
      Future.unit
