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
import com.mongodb.BasicDBObject
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.hashed
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoCollection
import uk.gov.hmrc.mongo.throttle.{ThrottleConfig, WithThrottling}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LibraryVersionRepository @Inject()(
    mongoComponent    : MongoComponent
  , futureHelpers     : FutureHelpers
  , val throttleConfig: ThrottleConfig
  )(implicit ec: ExecutionContext
  ) extends PlayMongoCollection[MongoLibraryVersion](
    collectionName = "libraryVersions"
  , mongoComponent = mongoComponent
  , domainFormat   = MongoLibraryVersion.format
  , indexes        = Seq(
                       IndexModel(hashed("libraryName"), IndexOptions().name("libraryNameIdx").background(true))
                     )
  ) with WithThrottling {

  val logger: Logger = Logger(this.getClass)

  def update(libraryVersion: MongoLibraryVersion): Future[MongoLibraryVersion] = {
    logger.debug(s"writing $libraryVersion")
    futureHelpers
      .withTimerAndCounter("mongo.update") {
        collection
          .replaceOne(
              filter      = equal("libraryName", libraryVersion.libraryName)
            , replacement = libraryVersion
            , options     = ReplaceOptions().upsert(true)
            )
          .toThrottledFuture
          .map(_ => libraryVersion)
    } recover {
      case lastError => throw new RuntimeException(s"failed to persist LibraryVersion: $libraryVersion", lastError)
    }
  }

  def getAllEntries: Future[Seq[MongoLibraryVersion]] =
    collection.find()
      .toThrottledFuture

  def clearAllData: Future[Boolean] =
    collection.deleteMany(new BasicDBObject())
      .toThrottledFuture
      .map(_.wasAcknowledged())
}
