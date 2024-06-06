/*
 * Copyright 2023 HM Revenue & Customs
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

import com.google.inject.Singleton

import javax.inject.Inject
import org.apache.pekko.actor.ActorSystem
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{ScheduledLockService, MongoLockRepository}
import uk.gov.hmrc.servicedependencies.service.LatestVersionService

import scala.concurrent.ExecutionContext

@Singleton
class LatestVersionScheduler @Inject()(
  configuration       : Configuration
, latestVersionService: LatestVersionService
, mongoLockRepository : MongoLockRepository
, timestampSupport    : TimestampSupport
)(using
  actorSystem         : ActorSystem
, applicationLifecycle: ApplicationLifecycle
, ec                  : ExecutionContext
) extends SchedulerUtils:

  private val schedulerConfigs =
    SchedulerConfig(configuration, "scheduler.latestVersion")

  private val lock: ScheduledLockService =
    ScheduledLockService(
      lockRepository    = mongoLockRepository
    , lockId            = "latest-versions-scheduler"
    , timestampSupport  = timestampSupport
    , schedulerInterval = schedulerConfigs.interval
    )

  given HeaderCarrier = HeaderCarrier()

  scheduleWithLock("Latest Version", schedulerConfigs, lock):
    logger.info("Updating latest versions")
    for
      _ <- latestVersionService.reloadLatestVersions()
      _ =  logger.info("Finished updating latest versions")
    yield ()
