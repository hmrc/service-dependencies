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
import org.joda.time.{DateTime, DateTimeZone, Instant}
import play.api.libs.json.{Json, OFormat}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.servicedependencies.model.{SlugInfo, MongoSlugInfoFormats}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugJobLastRunRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[SlugJobLastRunRepository.LastRun, BSONObjectID](
    collectionName = "lastSlugJobRun",
    mongo          = mongo.mongoConnector.db,
    domainFormat   = SlugJobLastRunRepository.format){

  import SlugJobLastRunRepository.LastRun

  def clearAllData: Future[Boolean] =
    super.removeAll().map(_.ok)

  def getLastRun: Future[Option[Instant]] =
    findAll().map(_.headOption.map(_.time.toInstant))

  def setLastRun(lastRun: Instant): Future[Boolean] =
    collection.update(
        selector = Json.obj(),
        update   = LastRun(new DateTime(lastRun, DateTimeZone.UTC)),
        upsert   = true)
      .map(_.n == 1)
}

object SlugJobLastRunRepository {
  case class LastRun(time: DateTime)

  implicit val format: OFormat[LastRun] = {
    import ReactiveMongoFormats.dateTimeFormats
    Json.format[LastRun]
  }
}
