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
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model.{SlugInfo, MongoSlugInfoFormats}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[SlugInfo, BSONObjectID](
    collectionName = "slugInfos",
    mongo          = mongo.mongoConnector.db,
    domainFormat   = MongoSlugInfoFormats.siFormat){

  import MongoSlugInfoFormats._

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] =
    Future.sequence(
      Seq(
        collection
          .indexesManager
          .ensure(
            Index(
              Seq("uri" -> IndexType.Ascending),
              name   = Some("slugInfoUniqueIdx"),
              unique = true)),
        collection
          .indexesManager
          .ensure(
            Index(
              Seq("name" -> IndexType.Hashed),
              name       = Some("slugInfoIdx"),
              background = true))))

  def add(slugInfo: SlugInfo): Future[Boolean] =
    collection
      .insert(slugInfo)
      .map(_ => true)
      .recover { case MongoErrors.Duplicate(_) => false }

  def getAllEntries: Future[Seq[SlugInfo]] =
    findAll()

  def clearAllData: Future[Boolean] =
    super.removeAll().map(_.ok)

  def getSlugInfos(name: String, optVersion: Option[String]): Future[Seq[SlugInfo]] =
    optVersion match {
      case None          => find("name" -> name)
      case Some(version) => find("name" -> name, "version" -> version)
    }
}
