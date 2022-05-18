/*
 * Copyright 2022 HM Revenue & Customs
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
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Updates
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Aggregates.{`match`, group, sort, unwind}
import org.mongodb.scala.model.Filters.{and, elemMatch, equal, gte, lte, or}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Sorts.descending
import org.mongodb.scala.model.{Aggregates, Filters, IndexModel, IndexOptions, ReplaceOptions, Sorts}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.{BobbyRuleQuery, BobbyRulesSummary}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BobbyRulesSummaryRepository @Inject()(
    mongoComponent: MongoComponent
  )(implicit ec: ExecutionContext
  ) extends PlayMongoRepository[BobbyRulesSummary](
    collectionName = "bobbyRulesSummary"
  , mongoComponent = mongoComponent
  , domainFormat   = BobbyRulesSummary.mongoFormat
  , indexes        = Seq(
                       IndexModel(ascending("date"), IndexOptions().name("dateIdx").unique(true))
                     )
  , optSchema      = Some(BsonDocument(BobbyRulesSummary.mongoSchema))
  ) {

  def add(summary: BobbyRulesSummary): Future[Unit] =
    collection
      .replaceOne(
          filter      = equal("date", summary.date)
        , replacement = summary
        , options     = ReplaceOptions().upsert(true)
        )
      .toFuture()
      .map(_ => ())

  def getLatest: Future[Option[BobbyRulesSummary]] =
    collection.find(equal("date", LocalDate.now))
      .toFuture()
      .map(_.headOption)
      .flatMap {
        case Some(a) => Future(Some(a))
        case None    => getMostRecent()
      }

  def getMostRecent() : Future[Option[BobbyRulesSummary]] =
    collection.find()
      .sort(descending("date"))
      .first()
      .toFuture()
      .map(Option.apply)
      .recover {
        case _ => None
      }

  // Not yet timebound
  def getHistoric(query: List[BobbyRuleQuery]): Future[Seq[BobbyRulesSummary]] = {
    val filters = Seq(
      query.map(q =>
        Filters.and(
          Filters.eq("summary.0.name", q.name),
          Filters.eq("summary.0.range", q.range),
          Filters.eq("summary.0.organisation", q.organisation)
        ))
    ).flatten

    collection.aggregate(Seq(
      unwind("$summary"),
      `match`(or(filters:_*)),
      group(
        "$_id",
        Accumulators.max("date", "$date"),
        Accumulators.push("summary", "$summary")
      ),
      sort(Sorts.descending("date"))
    ))
      .toFuture()
  }

  def clearAllData: Future[Unit] =
    collection.deleteMany(BsonDocument())
      .toFuture()
      .map(_ => ())
}
