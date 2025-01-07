/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.persistence.derived

import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.model.{Filters, Indexes, IndexModel, IndexOptions}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.servicedependencies.model.{BobbyReport, SlugInfoFlag}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedBobbyReportRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository[BobbyReport](
  collectionName = "DERIVED-bobby-report"
, mongoComponent = mongoComponent
, domainFormat   = BobbyReport.mongoFormat
, indexes        = IndexModel(
                     Indexes.ascending("repoName", "repoVersion"),
                     IndexOptions().name("uniqueIdx").unique(true)
                   ) :: IndexModel(Indexes.ascending("repoName"))
                     :: IndexModel(Indexes.ascending("repoVersion"))
                     :: SlugInfoFlag.values.map(f => IndexModel(Indexes.hashed(f.asString))).toList
, replaceIndexes = true
) with Transactions with Logging:

  // automatically refreshed when given new meta data artefacts from update scheduler
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration =
    TransactionConfiguration.strict

  def find(
    flag     : SlugInfoFlag,
    repoNames: Option[Seq[String]] = None,
  ): Future[Seq[BobbyReport]] =
    collection.find(Filters.and(
      Filters.equal(flag.asString, value = true)
    , repoNames.fold(Filters.empty)(names => Filters.in("repoName", names*))
    )).toFuture()

  def update(repoBobbyRules: BobbyReport): Future[Unit] =
    withSessionAndTransaction: session =>
      for
        _ <- collection.deleteMany(session, Filters.and(Filters.equal("repoName", repoBobbyRules.repoName), Filters.equal("repoVersion", repoBobbyRules.repoVersion.original))).toFuture()
        _ <- collection.insertOne(session, repoBobbyRules).toFuture()
      yield ()

  def delete(repoName: String): Future[Unit] =
    collection
      .deleteMany(Filters.equal("repoName", repoName))
      .toFuture()
      .map(_ => ())
