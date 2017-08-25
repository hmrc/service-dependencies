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
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers.withTimerAndCounter

import scala.concurrent.{ExecutionContext, Future}



trait RepositoryLibraryDependenciesRepository {
  def update(repositoryLibraryDependencies: MongoRepositoryDependencies): Future[MongoRepositoryDependencies]

  def getForRepository(repositoryName: String): Future[Option[MongoRepositoryDependencies]]
  def getAllEntries: Future[Seq[MongoRepositoryDependencies]]
  def clearAllData: Future[Boolean]
}

class MongoRepositoryLibraryDependenciesRepository(mongo: () => DB)
  extends ReactiveRepository[MongoRepositoryDependencies, BSONObjectID](
    collectionName = "repositoryLibraryDependencies",
    mongo = mongo,
    domainFormat = MongoRepositoryDependencies.format) with RepositoryLibraryDependenciesRepository {

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] = localEnsureIndexes


  private def localEnsureIndexes = {
    Future.sequence(
      Seq(
        collection.indexesManager.ensure(Index(Seq("repositoryName" -> IndexType.Hashed), name = Some("RepositoryNameIdx"), unique = true))
      )
    )
  }

  override def update(repositoryLibraryDependencies: MongoRepositoryDependencies): Future[MongoRepositoryDependencies] = {

    logger.info(s"writing to mongo: $repositoryLibraryDependencies")

    import reactivemongo.play.json.ImplicitBSONHandlers._

    withTimerAndCounter("mongo.update") {
      for {
        update <- collection.update(selector = Json.obj("repositoryName" -> Json.toJson(repositoryLibraryDependencies.repositoryName)), update = repositoryLibraryDependencies, upsert = true)
      } yield update match {
        case lastError if !lastError.ok => throw new RuntimeException(s"failed to persist RepositoryLibraryDependencies: $repositoryLibraryDependencies")
        case _ => repositoryLibraryDependencies
      }
    } recover {
      case e => throw new RuntimeException(s"failed to persist RepositoryLibraryDependencies: $repositoryLibraryDependencies", e)
    }
  }


  override def getForRepository(repositoryName: String): Future[Option[MongoRepositoryDependencies]] = {
    withTimerAndCounter("mongo.read") {
      import reactivemongo.play.json.ImplicitBSONHandlers.BSONDocumentFormat
      find("repositoryName" -> BSONDocument("$eq" -> repositoryName)) map {
        case data if data.size > 1 => throw new RuntimeException(s"There should only be '1' record per repository! for $repositoryName there are ${data.size}")
        case data => data.headOption
      }
    }

  }

  override def getAllEntries: Future[Seq[MongoRepositoryDependencies]] = {
    logger.info("retrieving getAll current dependencies")
    findAll()
  }


  override def clearAllData: Future[Boolean] = super.removeAll().map(lastError => lastError.ok && lastError.writeErrors.isEmpty && lastError.writeConcernError.isEmpty)

}
