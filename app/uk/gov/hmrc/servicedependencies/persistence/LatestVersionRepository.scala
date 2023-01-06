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

package uk.gov.hmrc.servicedependencies.persistence

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{Indexes, IndexModel, IndexOptions, ReplaceOptions}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LatestVersionRepository @Inject()(
    mongoComponent: MongoComponent
  )(implicit ec: ExecutionContext
  ) extends PlayMongoRepository[LatestVersion](
    collectionName = "dependencyVersions"
  , mongoComponent = mongoComponent
  , domainFormat   = LatestVersion.mongoFormat
  , indexes        = Seq(
                       IndexModel(Indexes.ascending("name", "group"), IndexOptions().unique(true))
                     )
  , optSchema      = Some(BsonDocument(LatestVersion.schema))
  ) with Logging {

  def update(latestVersion: LatestVersion): Future[Unit] = {
    logger.debug(s"writing $latestVersion")
    collection
      .replaceOne(
          filter      = and( equal("name" , latestVersion.name)
                           , equal("group", latestVersion.group)
                           )
        , replacement = latestVersion
        , options     = ReplaceOptions().upsert(true)
        )
      .toFuture()
      .map(_ => ())
  }

  def getAllEntries: Future[Seq[LatestVersion]] =
    collection.find()
      .toFuture()

  def find(group: String, artefact: String): Future[Option[LatestVersion]] =
    collection.find(and(equal("group", group), equal("name", artefact)))
      .toFuture()
      .map(_.headOption)

  def clearAllData: Future[Unit] =
    collection.deleteMany(BsonDocument())
      .toFuture()
      .map(_ => ())
}
