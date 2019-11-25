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

import java.time.Instant

import com.google.inject.{Inject, Singleton}
import com.mongodb.BasicDBObject
import org.mongodb.scala.model.Filters.{equal, regex}
import org.mongodb.scala.model.Indexes.hashed
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoCollection
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
@Singleton
class RepositoryLibraryDependenciesRepository @Inject()(mongo: MongoComponent, futureHelper: FutureHelpers)
    extends PlayMongoCollection[MongoRepositoryDependencies](
      collectionName = "repositoryLibraryDependencies",
      mongoComponent = mongo,
      domainFormat   = MongoRepositoryDependencies.format,
      indexes = Seq(
        IndexModel(hashed("repositoryName"), IndexOptions().name("RepositoryNameIdx").background(true))
      )
    ) {

  val logger: Logger = Logger(this.getClass)

  def update(repositoryLibraryDependencies: MongoRepositoryDependencies): Future[MongoRepositoryDependencies] = {
    logger.info(s"writing to mongo: $repositoryLibraryDependencies")
    futureHelper
      .withTimerAndCounter("mongo.update") {
        collection
          .replaceOne(
            filter = equal("repositoryName", repositoryLibraryDependencies.repositoryName),
            repositoryLibraryDependencies,
            ReplaceOptions().upsert(true)
          )
          .toFuture()
          .map(_ => repositoryLibraryDependencies)
      }
      .recover {
        case lastError =>
          throw new RuntimeException(
            s"failed to persist RepositoryLibraryDependencies: $repositoryLibraryDependencies",
            lastError)
      }
  }

  def getForRepository(repositoryName: String): Future[Option[MongoRepositoryDependencies]] =
    futureHelper.withTimerAndCounter("mongo.read") {
      // Note, the regex will not use the index.
      // Mongdo 3.4 does support collated indices, allowing for case-insensitive searches: https://docs.mongodb.com/manual/core/index-case-insensitive/
      //TODO: Explore using index for this query
      collection.find(regex("repositoryName", "^" + repositoryName + "$", "i")).toFuture.map {
        case data if data.size > 1 =>
          throw new RuntimeException(
            s"There should only be '1' record per repository! for $repositoryName there are ${data.size}")
        case data => data.headOption
      }
    }

  def getAllEntries: Future[Seq[MongoRepositoryDependencies]] = {
    logger.debug("retrieving getAll current dependencies")
    collection.find().toFuture()
  }

  def clearAllData: Future[Boolean] = collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())

  def clearUpdateDates: Future[Seq[MongoRepositoryDependencies]] =
    getAllEntries.flatMap { es =>
      Future.sequence(es.map(mrd => update(mrd.copy(updateDate = Instant.EPOCH))))
    }
}
