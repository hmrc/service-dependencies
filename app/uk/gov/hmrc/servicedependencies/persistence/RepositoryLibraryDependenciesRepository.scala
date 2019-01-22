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
import org.joda.time.{DateTime, DateTimeZone}

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONObjectID, BSONRegex}
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}
@Singleton
class RepositoryLibraryDependenciesRepository @Inject()(mongo: ReactiveMongoComponent, futureHelper: FutureHelpers)
    extends ReactiveRepository[MongoRepositoryDependencies, BSONObjectID](
      collectionName = "repositoryLibraryDependencies",
      mongo          = mongo.mongoConnector.db,
      domainFormat   = MongoRepositoryDependencies.format) {

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] =
    Future.sequence(
      Seq(
        collection.indexesManager.ensure(
          Index(
            Seq("repositoryName" -> IndexType.Hashed),
            name       = Some("RepositoryNameIdx"),
            unique     = true,
            background = true))
      )
    )

  def update(repositoryLibraryDependencies: MongoRepositoryDependencies): Future[MongoRepositoryDependencies] = {
    logger.info(s"writing to mongo: $repositoryLibraryDependencies")
    futureHelper.withTimerAndCounter("mongo.update") {
      collection.update(
          selector = Json.obj("repositoryName" -> Json.toJson(repositoryLibraryDependencies.repositoryName)),
          update   = repositoryLibraryDependencies,
          upsert   = true)
        .map(_ => repositoryLibraryDependencies)
    }.recover {
      case lastError =>
        throw new RuntimeException(
          s"failed to persist RepositoryLibraryDependencies: $repositoryLibraryDependencies",
          lastError)
    }
  }

  def getForRepository(repositoryName: String): Future[Option[MongoRepositoryDependencies]] =
    futureHelper.withTimerAndCounter("mongo.read") {
      find("repositoryName" -> BSONRegex("^" + repositoryName + "$", "i")).map {
        case data if data.size > 1 =>
          throw new RuntimeException(
            s"There should only be '1' record per repository! for $repositoryName there are ${data.size}")
        case data => data.headOption
      }
    }

  def getAllEntries: Future[Seq[MongoRepositoryDependencies]] = {
    logger.debug("retrieving getAll current dependencies")
    findAll()
  }

  def clearAllData: Future[Boolean] = super.removeAll().map(_.ok)

  def clearUpdateDates: Future[Seq[MongoRepositoryDependencies]] =
    getAllEntries.flatMap { es =>
      Future.sequence(es.map(mrd => update(mrd.copy(updateDate = new DateTime(0, DateTimeZone.UTC)))))
    }
}
