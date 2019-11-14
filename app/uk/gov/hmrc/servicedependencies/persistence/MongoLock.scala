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

package uk.gov.hmrc.servicedependencies.persistence

import java.util.concurrent.TimeUnit

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.mongo.component.{MongoComponent, PlayMongoComponent}
import uk.gov.hmrc.mongo.lock.{CurrentTimestampSupport, MongoLockRepository, MongoLockService}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

class MongoLock(db: MongoComponent, lockId_ : String)(implicit ec: ExecutionContext) extends MongoLockService {

  override val ttl: Duration = Duration(60, TimeUnit.MINUTES)

  override val mongoLockRepository: MongoLockRepository = new MongoLockRepository(db, new CurrentTimestampSupport())

  override val lockId: String = lockId_
}

@Singleton
class MongoLocks @Inject()(mongo: PlayMongoComponent)(implicit ec: ExecutionContext) {
  val repositoryDependencyMongoLock   = new MongoLock(mongo, "repository-dependencies-data-reload-job")
  val libraryMongoLock                = new MongoLock(mongo, "libraries-data-reload-job")
  val sbtPluginMongoLock              = new MongoLock(mongo, "sbt-plugin-data-reload-job")
  val slugMetadataUpdateSchedulerLock = new MongoLock(mongo, "slug-job-scheduler")
  val bobbyRulesSummarySchedulerLock  = new MongoLock(mongo, "bobby-rules-summary-scheduler")
}
