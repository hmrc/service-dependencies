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

import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import reactivemongo.api.DB
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.util.FutureHelpers.withTimerAndCounter
import uk.gov.hmrc.servicedependencies.model._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{ExecutionContext, Future}



trait RepositoryLibraryDependenciesRepository {
  def update(repositoryLibraryDependencies: RepositoryLibraryDependencies): Future[RepositoryLibraryDependencies]

  def getForRepository(repositoryName: String): Future[Option[RepositoryLibraryDependencies]]
  def getAllDependencyEntries: Future[List[RepositoryLibraryDependencies]]
  def clearAllData: Future[Boolean]
}

class MongoRepositoryLibraryDependenciesRepository(mongo: () => DB)
  extends ReactiveRepository[RepositoryLibraryDependencies, BSONObjectID](
    collectionName = "repositoryLibraryDependencies",
    mongo = mongo,
    domainFormat = RepositoryLibraryDependencies.format) with RepositoryLibraryDependenciesRepository {

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] = localEnsureIndexes


  private def localEnsureIndexes = {
    Future.sequence(
      Seq(
        collection.indexesManager.ensure(Index(Seq("repositoryName" -> IndexType.Hashed), name = Some("RepositoryNameIdx"), unique = true))
      )
    )
  }

  override def update(repositoryLibraryDependencies: RepositoryLibraryDependencies): Future[RepositoryLibraryDependencies] = {

    logger.info(s"writing to mongo: $repositoryLibraryDependencies")

    withTimerAndCounter("mongo.update") {
      for {
        update <- collection.update(selector = Json.obj("repositoryName" -> Json.toJson(repositoryLibraryDependencies.repositoryName)), update = repositoryLibraryDependencies, upsert = true)
      } yield update match {
        case lastError if lastError.inError => throw new RuntimeException(s"failed to persist RepositoryLibraryDependencies: $repositoryLibraryDependencies")
        case _ => repositoryLibraryDependencies
      }
    }
  }


  override def getForRepository(repositoryName: String): Future[Option[RepositoryLibraryDependencies]] = {
    withTimerAndCounter("mongo.read") {
      find("repositoryName" -> BSONDocument("$eq" -> repositoryName)) map {
        case data if data.size > 1 => throw new RuntimeException(s"There should only be '1' record per repository! for $repositoryName there are ${data.size}")
        case data => data.headOption
      }
    }

  }

  override def getAllDependencyEntries: Future[List[RepositoryLibraryDependencies]] = findAll()

  override def clearAllData: Future[Boolean] = super.removeAll().map(!_.hasErrors)

}
