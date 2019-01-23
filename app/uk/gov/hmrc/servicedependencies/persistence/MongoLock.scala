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

import com.google.inject.{Inject, Singleton}
import org.joda.time.Duration
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.{DB, DefaultDB}
import uk.gov.hmrc.lock.{LockKeeper, LockMongoRepository, LockRepository}

class MongoLock(db: () => DB, lockId_ : String) extends LockKeeper {
  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(60)

  override def repo: LockRepository = LockMongoRepository(db)

  override def lockId: String = lockId_
}

@Singleton
class MongoLocks @Inject()(mongo: ReactiveMongoComponent) {
  private val db = mongo.mongoConnector.db

  val repositoryDependencyMongoLock = new MongoLock(db, "repository-dependencies-data-reload-job")
  val libraryMongoLock              = new MongoLock(db, "libraries-data-reload-job")
  val sbtPluginMongoLock            = new MongoLock(db, "sbt-plugin-data-reload-job")
  val slugJobSchedulerLock          = new MongoLock(db, "slug-job-scheduler")
}
