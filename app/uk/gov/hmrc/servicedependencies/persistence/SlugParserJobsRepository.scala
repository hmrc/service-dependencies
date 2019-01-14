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
import uk.gov.hmrc.servicedependencies.model.MongoSlugParserJob
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugParserJobsRepository @Inject()(mongo: ReactiveMongoComponent, futureHelpers: FutureHelpers)
    extends ReactiveRepository[MongoSlugParserJob, BSONObjectID](
      collectionName = "slugParserJobs",
      mongo          = mongo.mongoConnector.db,
      domainFormat   = MongoSlugParserJob.format) {


  import ExecutionContext.Implicits.global

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] =
    localEnsureIndexes

  private def localEnsureIndexes =
    // TODO require index?
    Future.sequence(
      Seq(
        collection
          .indexesManager
          .ensure(
            Index(
              Seq("id" -> IndexType.Hashed),
              name       = Some("idIdx"),
              unique     = true,
              background = true))
      )
    )

  def add(slugParserJob: MongoSlugParserJob): Future[Unit] =
    collection
      .insert(
        slugParserJob.copy(
          id = slugParserJob.id.orElse(Some(UUID.randomUUID().toString))
        ))
      .map(_ => ())

  // TODO remove this
  def newMSLJ(i: String) = MongoSlugParserJob(
    id          = None,
    slugName    = s"slugName$i",
    libraryName = s"libraryName$i",
    version     = s"version$i")
  add(newMSLJ("0"))
  add(newMSLJ("1"))
  add(newMSLJ("2"))
  add(newMSLJ("3"))


  def delete(id: String): Future[Unit] =
    collection
      .remove(Json.obj("_id" -> id))
      .map(_ => ())

  def getAllEntries: Future[Seq[MongoSlugParserJob]] = findAll()

  def clearAllData: Future[Boolean] = super.removeAll().map(_.ok)
}
