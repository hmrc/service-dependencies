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
import java.util.UUID
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, NewSlugParserJob}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugParserJobsRepository @Inject()(mongo: ReactiveMongoComponent)
    extends ReactiveRepository[MongoSlugParserJob, BSONObjectID](
      collectionName = "slugParserJobs",
      mongo          = mongo.mongoConnector.db,
      domainFormat   = MongoSlugParserJob.format) {


  import ExecutionContext.Implicits.global

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("slugUri" -> IndexType.Ascending),
        name       = Some("slugParserJobUniqueIdx"),
        unique     = true),
      Index(
        Seq("processed" -> IndexType.Hashed),
        name       = Some("slugParserJobProcessedIdx"),
        background = true))

  def add(newJob: NewSlugParserJob): Future[Boolean] =
    collection
      .insert(
        MongoSlugParserJob(
          slugUri       = newJob.slugUri,
          processed     = newJob.processed,
          attempts      = 0
        ))
      .map(_ => true)
      .recover { case MongoErrors.Duplicate(_) =>  false }

  def markProcessed(slugUri: String): Future[Unit] = {
    logger.info(s"mark job $slugUri as processed")
    collection
      .update(
        selector = Json.obj("slugUri" -> slugUri),
        update   = Json.obj("$set"    -> Json.obj("processed" -> true)))
      .map(_ => ())
  }

  def markAttempted(slugUri: String): Future[Boolean] = {
    logger.info(s"mark job $slugUri as attempted")
    collection
      .update(
        selector = Json.obj("slugUri" -> slugUri),
        update   = Json.obj("$inc"    -> Json.obj("attempts" -> 1)))
      .map(_.nModified == 1)
  }

  def getAllEntries: Future[Seq[MongoSlugParserJob]] =
    findAll()

  def getUnprocessed: Future[Seq[MongoSlugParserJob]] =
    find("processed" -> false,
         "attempts" -> Json.obj("$lt" -> 3))

  def clearAllData: Future[Boolean] =
    super.removeAll().map(_.ok)
}
