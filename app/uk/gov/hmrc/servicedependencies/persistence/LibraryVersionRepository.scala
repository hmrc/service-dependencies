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
import play.api.libs.json.Json
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.util.FutureHelpers
import uk.gov.hmrc.servicedependencies.model._
import scala.concurrent.ExecutionContext.Implicits.global
import play.modules.reactivemongo.ReactiveMongoComponent

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LibraryVersionRepository @Inject()(mongo: ReactiveMongoComponent, futureHelpers: FutureHelpers)
    extends ReactiveRepository[MongoLibraryVersion, BSONObjectID](
      collectionName = "libraryVersions",
      mongo          = mongo.mongoConnector.db,
      domainFormat   = MongoLibraryVersion.format) {

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] =
    Future.sequence(
      Seq(
        collection
          .indexesManager
          .ensure(
            Index(
              Seq("libraryName" -> IndexType.Hashed),
              name       = Some("libraryNameIdx"),
              unique     = true,
              background = true))
      )
    )

  def update(libraryVersion: MongoLibraryVersion): Future[MongoLibraryVersion] = {
    logger.debug(s"writing $libraryVersion")
    futureHelpers
      .withTimerAndCounter("mongo.update") {
        collection.update(
            selector = Json.obj("libraryName" -> Json.toJson(libraryVersion.libraryName)),
            update   = libraryVersion,
            upsert   = true)
          .map(_ => libraryVersion)
    } recover {
      case lastError => throw new RuntimeException(s"failed to persist LibraryVersion: $libraryVersion", lastError)
    }
  }

  def getAllEntries: Future[Seq[MongoLibraryVersion]] = findAll()

  def clearAllData: Future[Boolean] = super.removeAll().map(_.ok)
}
