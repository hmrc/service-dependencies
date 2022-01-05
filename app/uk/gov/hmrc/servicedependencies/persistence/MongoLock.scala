/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.persistence

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, LockService}

import scala.concurrent.duration._

@Singleton
class MongoLocks @Inject()(
  mongoLockRepository: MongoLockRepository
) {
  val dependencyReloadSchedulerLock     = LockService(mongoLockRepository, "dependency-reload-scheduler"        , 1.hour)
  val latestVersionsReloadSchedulerLock = LockService(mongoLockRepository, "dependencyVersions-reload-scheduler", 1.hour)
  val slugMetadataUpdateSchedulerLock   = LockService(mongoLockRepository, "slug-job-scheduler"                 , 1.hour)
  val bobbyRulesSummarySchedulerLock    = LockService(mongoLockRepository, "bobby-rules-summary-scheduler"      , 1.hour)
}
