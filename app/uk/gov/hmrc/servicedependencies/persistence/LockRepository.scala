/*
 * Copyright 2021 HM Revenue & Customs
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
import org.mongodb.scala.bson.BsonDocument
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, Lock}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LocksRepository @Inject()(
    mongoLockRepository: MongoLockRepository
  )(implicit ec: ExecutionContext
  ) {

  def getAllEntries: Future[Seq[Lock]] =
    mongoLockRepository.collection.find()
      .toFuture

  def clearAllData: Future[Boolean] =
    mongoLockRepository.collection.deleteMany(BsonDocument())
      .toFuture
      .map(_.wasAcknowledged())
}
