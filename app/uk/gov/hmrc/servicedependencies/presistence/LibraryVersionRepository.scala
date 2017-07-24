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

import play.api.libs.json.Json
import reactivemongo.api.DB
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.util.FutureHelpers.withTimerAndCounter
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}



trait LibraryVersionRepository {

  def update(libraryVersion: MongoLibraryVersion): Future[MongoLibraryVersion]
  def getAllDependencyEntries: Future[List[MongoLibraryVersion]]
  def clearAllData: Future[Boolean]
}

class MongoLibraryVersionRepository(mongo: () => DB)
  extends ReactiveRepository[MongoLibraryVersion, BSONObjectID](
    collectionName = "libraryVersions",
    mongo = mongo,
    domainFormat = MongoLibraryVersion.format) with LibraryVersionRepository {


  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] =
    Future.sequence(
      Seq(
        collection.indexesManager.ensure(Index(Seq("libraryName" -> IndexType.Hashed), name = Some("libraryNameIdx"), unique = true))
      )
    )

  override def  update(libraryVersion: MongoLibraryVersion): Future[MongoLibraryVersion] = {

    logger.info(s"writing $libraryVersion")
    withTimerAndCounter("mongo.update") {
      for {
        update <- collection.update(selector = Json.obj("libraryName" -> Json.toJson(libraryVersion.libraryName)), update = libraryVersion, upsert = true)
      } yield update match {
        case lastError if lastError.inError => throw new RuntimeException(s"failed to persist LibraryVersion: $libraryVersion")
        case _ => libraryVersion
      }
    }
  }

  override def getAllDependencyEntries: Future[List[MongoLibraryVersion]] = findAll()

  override def clearAllData: Future[Boolean] = super.removeAll().map(!_.hasErrors)
}

