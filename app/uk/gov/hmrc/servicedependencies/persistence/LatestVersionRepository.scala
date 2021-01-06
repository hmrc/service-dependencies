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
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{Indexes, IndexModel, IndexOptions, ReplaceOptions}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LatestVersionRepository @Inject()(
    mongoComponent: MongoComponent
  , futureHelpers : FutureHelpers
  )(implicit ec: ExecutionContext
  ) extends PlayMongoRepository[MongoLatestVersion](
    collectionName = "dependencyVersions"
  , mongoComponent = mongoComponent
  , domainFormat   = MongoLatestVersion.format
  , indexes        = Seq(
                       IndexModel(Indexes.ascending("name", "group"), IndexOptions().unique(true))
                     )
  , optSchema      = Some(BsonDocument(MongoLatestVersion.schema))
  ) with Logging {

  def update(latestVersion: MongoLatestVersion): Future[Unit] = {
    logger.debug(s"writing $latestVersion")
    futureHelpers
      .withTimerAndCounter("mongo.update") {
        collection
          .replaceOne(
              filter      = and( equal("name" , latestVersion.name)
                               , equal("group", latestVersion.group)
                               )
            , replacement = latestVersion
            , options     = ReplaceOptions().upsert(true)
            )
          .toFuture
          .map(_ => ())
    } recover {
      case e =>
        throw new RuntimeException(s"failed to persist latest version $latestVersion: ${e.getMessage}", e)
    }
  }

  def getAllEntries: Future[Seq[MongoLatestVersion]] =
    collection.find()
      .toFuture

  def clearAllData: Future[Boolean] =
    collection.deleteMany(BsonDocument())
      .toFuture
      .map(_.wasAcknowledged())
}
