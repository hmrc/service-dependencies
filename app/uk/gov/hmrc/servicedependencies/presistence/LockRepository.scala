/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.presistence

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import reactivemongo.api.DB
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.lock.LockFormats
import uk.gov.hmrc.lock.LockFormats.Lock
import uk.gov.hmrc.mongo.ReactiveRepository

import scala.concurrent.Future



trait LocksRepository {
  def getAllEntries: Future[Seq[Lock]]
  def clearAllData: Future[Boolean]
}

class MongoLocksRepository(mongo: () => DB)
  extends ReactiveRepository[Lock, BSONObjectID](
    collectionName = "locks",
    mongo = mongo,
    domainFormat = LockFormats.format) with LocksRepository {


  override def getAllEntries: Future[Seq[Lock]] = findAll()

  override def clearAllData: Future[Boolean] = super.removeAll().map(_.ok)
}

