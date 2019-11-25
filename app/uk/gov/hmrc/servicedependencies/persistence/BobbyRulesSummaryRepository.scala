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

import java.time.LocalDate

import com.google.inject.{Inject, Singleton}
import com.mongodb.BasicDBObject
import org.mongodb.scala._
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Sorts.descending
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoCollection
import uk.gov.hmrc.servicedependencies.model.BobbyRulesSummary

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BobbyRulesSummaryRepository @Inject()(mongo: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoCollection[BobbyRulesSummary](
      collectionName = "bobbyRulesSummary",
      mongoComponent = mongo,
      domainFormat   = BobbyRulesSummary.mongoFormat,
      indexes = Seq(
        IndexModel(ascending("date"), IndexOptions().name("dateIdx").unique(true))
      )
    ) {

  private implicit val brsf = BobbyRulesSummary.mongoFormat

  def add(summary: BobbyRulesSummary): Future[Unit] =
    collection
      .replaceOne(
        filter = equal("date", summary.date),
        summary,
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def getLatest: Future[Option[BobbyRulesSummary]] =
    collection.find(equal("date", LocalDate.now)).toFuture()
      .map(_.headOption)
      .flatMap {
        case Some(a) => Future(Some(a))
        case None    => getHistoric.map(_.headOption)
      }

  // Not time bound yet
  def getHistoric: Future[List[BobbyRulesSummary]] =
    collection
      .find()
      .sort(descending("date")).toFuture().map(_.toList)

  def clearAllData: Future[Boolean] =
    collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())

}
