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

import com.google.inject.{Inject, Singleton}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers.withTimerAndCounter

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class RepositoryLibraryDependenciesRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[MongoRepositoryDependencies, BSONObjectID](
    collectionName = "repositoryLibraryDependencies",
    mongo = mongo.mongoConnector.db,
    domainFormat = MongoRepositoryDependencies.format) {

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] = localEnsureIndexes


  private def localEnsureIndexes = {
    Future.sequence(
      Seq(
        collection.indexesManager.ensure(Index(Seq("repositoryName" -> IndexType.Hashed), name = Some("RepositoryNameIdx"), unique = true))
      )
    )
  }

  def update(repositoryLibraryDependencies: MongoRepositoryDependencies): Future[MongoRepositoryDependencies] = {

    logger.info(s"writing to mongo: $repositoryLibraryDependencies")

    withTimerAndCounter("mongo.update") {
      for {
        update <- collection.update(selector = Json.obj("repositoryName" -> Json.toJson(repositoryLibraryDependencies.repositoryName)), update = repositoryLibraryDependencies, upsert = true)
      } yield update match {
        case _ => repositoryLibraryDependencies
      }
    } recover {
      case lastError => throw new RuntimeException(s"failed to persist RepositoryLibraryDependencies: $repositoryLibraryDependencies", lastError)
    }
  }


  def getForRepository(repositoryName: String): Future[Option[MongoRepositoryDependencies]] = {
    withTimerAndCounter("mongo.read") {
      find("repositoryName" -> BSONDocument("$eq" -> repositoryName)) map {
        case data if data.size > 1 => throw new RuntimeException(s"There should only be '1' record per repository! for $repositoryName there are ${data.size}")
        case data => data.headOption
      }
    }

  }

  def getAllEntries: Future[Seq[MongoRepositoryDependencies]] = {
    logger.debug("retrieving getAll current dependencies")
    findAll()
  }


  def clearAllData: Future[Boolean] = super.removeAll().map(_.ok)

  def clearAllGithubLastUpdateDates: Future[Seq[MongoRepositoryDependencies]] = {
    getAllEntries.flatMap { es =>
      Future.sequence(es.map(mrd => update(mrd.copy(lastGitUpdateDate = None))))
    }
  }
}
