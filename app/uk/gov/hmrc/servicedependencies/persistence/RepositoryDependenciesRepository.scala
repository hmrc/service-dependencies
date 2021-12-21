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

import java.time.Instant

import cats.implicits._
import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{Collation, CollationStrength, IndexModel, IndexOptions, ReplaceOptions}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}

import RepositoryDependenciesRepository.caseInsensitiveCollation

@Singleton
class RepositoryDependenciesRepository @Inject()(
    mongoComponent: MongoComponent
  , futureHelper  : FutureHelpers
  )(implicit ec: ExecutionContext
  ) extends PlayMongoRepository[MongoRepositoryDependencies](
    collectionName = "repositoryLibraryDependencies"
  , mongoComponent = mongoComponent
  , domainFormat   = MongoRepositoryDependencies.format
  , indexes        = Seq(
                         IndexModel(
                           ascending("repositoryName")
                         , IndexOptions()
                           .name("RepositoryNameIdx")
                           .collation(caseInsensitiveCollation)
                           .background(true)
                           .unique(true)
                         )
                     )
  , replaceIndexes = true
  , optSchema      = Some(BsonDocument(MongoRepositoryDependencies.schema))
  ) with Logging {

  def update(repositoryLibraryDependencies: MongoRepositoryDependencies): Future[MongoRepositoryDependencies] = {
    logger.info(s"writing to mongo: $repositoryLibraryDependencies")
    futureHelper
      .withTimerAndCounter("mongo.update") {
        collection
          .replaceOne(
              filter      = equal("repositoryName", repositoryLibraryDependencies.repositoryName)
            , replacement = repositoryLibraryDependencies
            , options     = ReplaceOptions().upsert(true).collation(caseInsensitiveCollation)
            )
          .toFuture
          .map(_ => repositoryLibraryDependencies)
      }
      .recover {
        case e =>
          throw new RuntimeException(s"failed to persist RepositoryLibraryDependencies $repositoryLibraryDependencies ${e.getMessage}", e)
      }
  }

  def getForRepository(repositoryName: String): Future[Option[MongoRepositoryDependencies]] =
    futureHelper.withTimerAndCounter("mongo.read") {
      collection
        .find(equal("repositoryName", repositoryName))
        .collation(caseInsensitiveCollation)
        .toFuture
        .map(_.headOption)
    }

  def getAllEntries: Future[Seq[MongoRepositoryDependencies]] = {
    logger.debug("retrieving getAll current dependencies")
    collection.find()
      .toFuture
  }

  def clearAllData: Future[Unit] =
    collection.deleteMany(BsonDocument())
      .toFuture
      .map(_ => ())

  def clearUpdateDates: Future[Seq[MongoRepositoryDependencies]] =
    for {
      entries <- getAllEntries
      res     <- entries.toList.traverse(mrd => update(mrd.copy(updateDate = Instant.EPOCH)))
    } yield res

  def clearUpdateDatesForRepository(repositoryName: String): Future[Option[MongoRepositoryDependencies]] =
    for {
      entries <- getForRepository(repositoryName)
      res     <- entries.traverse(mrd => update(mrd.copy(updateDate = Instant.EPOCH)))
    } yield res
}

object RepositoryDependenciesRepository {
  val caseInsensitiveCollation: Collation =
    Collation.builder
      .locale("en")
      .collationStrength(CollationStrength.SECONDARY)
      .build
}
