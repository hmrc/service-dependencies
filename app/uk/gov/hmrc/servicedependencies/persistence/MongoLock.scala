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
import uk.gov.hmrc.mongo.component.PlayMongoComponent
import uk.gov.hmrc.mongo.lock.MongoLockRepository

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

@Singleton
class MongoLocks @Inject()(mongo: PlayMongoComponent, mongoLockRepository: MongoLockRepository)(
  implicit ec: ExecutionContext) {
  val oneHour                         = Duration(60, TimeUnit.MINUTES)
  val repositoryDependencyMongoLock   = mongoLockRepository.toService("repository-dependencies-data-reload-job", oneHour)
  val libraryMongoLock                = mongoLockRepository.toService("libraries-data-reload-job", oneHour)
  val sbtPluginMongoLock              = mongoLockRepository.toService("sbt-plugin-data-reload-job", oneHour)
  val slugMetadataUpdateSchedulerLock = mongoLockRepository.toService("slug-job-scheduler", oneHour)
  val bobbyRulesSummarySchedulerLock  = mongoLockRepository.toService("bobby-rules-summary-scheduler", oneHour)
}
