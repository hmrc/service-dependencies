/*
 * Copyright 2023 HM Revenue & Customs
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

import play.api.Logging
import org.mongodb.scala.{ClientSession, ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Aggregates, Filters, IndexModel, IndexOptions, Indexes, Projections, ReplaceOptions, Sorts, UpdateOptions, Updates}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, Version}

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetaArtefactRepository @Inject()(
  final val mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[MetaArtefact](
  collectionName = "metaArtefacts",
  mongoComponent = mongoComponent,
  domainFormat   = MetaArtefact.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("name", "version"), IndexOptions().unique(true)),
                     IndexModel(Indexes.compoundIndex(Indexes.ascending("name"), Indexes.hashed("latest"))),
                     IndexModel(Indexes.ascending("name"), IndexOptions().unique(true).partialFilterExpression(Filters.equal("latest", true))),
                     IndexModel(Indexes.hashed("latest"))
                   ),
  replaceIndexes = true
) with Transactions
  with Logging:

  // MetaArtefacts are removed when ArtefactProcess detects they've been deleted (or the related Slug) from Artifactory
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration =
    TransactionConfiguration.strict

  def put(meta: MetaArtefact): Future[Unit] =
    withSessionAndTransaction: session =>
      for
        _    <- collection
                  .replaceOne(
                    clientSession = session
                  , filter        = Filters.and(
                                      Filters.equal("name"   , meta.name)
                                    , Filters.equal("version", meta.version.toString)
                                    )
                  , replacement   = meta.copy(latest = false) // this will be set appropriately next
                  , options       = ReplaceOptions().upsert(true)
                  )
                  .toFuture()
        oMax <- maxVersion(meta.name, session)
        _    <- oMax.fold(Future.unit)(v => markLatest(meta.name, v, session))
      yield ()

  def delete(repositoryName: String, version: Version): Future[Unit] =
    withSessionAndTransaction: session =>
      for
        _    <- collection
                  .deleteOne(
                    clientSession = session
                  , filter        = Filters.and(
                                      Filters.equal("name"   , repositoryName)
                                    , Filters.equal("version", version.toString)
                                    )
                  )
                  .toFuture()
        oMax <- maxVersion(repositoryName, session)
        _    <- oMax.fold(Future.unit)(v => markLatest(repositoryName, v, session))
      yield ()

  private def maxVersion(repositoryName: String, session: ClientSession): Future[Option[Version]] =
    collection
      .aggregate[BsonDocument](
        clientSession = session
      , pipeline      = List(
                          Aggregates.`match`(Filters.equal("name", repositoryName))
                        , Aggregates.project(Projections.fields( Projections.excludeId(), Projections.include("version")))
                        )
      )
      .toFuture()
      .map(_.map(b => Version(b.getString("version").getValue)))
      .map(vs => if (vs.nonEmpty) Some(vs.max) else None)

  private def clearLatest(name: String, session: ClientSession): Future[Unit] =
    collection
      .updateMany(
        clientSession = session
      , filter        = Filters.equal("name", name)
      , update        = Updates.set("latest", false)
      )
      .toFuture()
      .map(_ => ())

  private def markLatest(name: String, version: Version, session: ClientSession): Future[Unit] =
    for
      _ <- clearLatest(name, session)
      _ <- collection
             .updateOne(
               clientSession = session
             , filter        = Filters.and(
                                 Filters.equal("name", name)
                               , Filters.equal("version", version.original)
                               )
             , update        = Updates.set("latest", true)
             , options       = UpdateOptions().upsert(true)
             )
             .toFuture()
    yield ()

  def find(repositoryName: String): Future[Option[MetaArtefact]] =
    collection
      .find(
        Filters.and(
          Filters.equal("name"  , repositoryName)
        , Filters.equal("latest", true)
        )
      )
      .headOption()

  def find(repositoryName: String, version: Version): Future[Option[MetaArtefact]] =
    collection
      .find(
        Filters.and(
          Filters.equal("name"   , repositoryName)
        , Filters.equal("version", version.toString)
        )
      )
      .headOption()

  def findAllVersions(repositoryName: String): Future[Seq[MetaArtefact]] =
    collection
      .find(filter = Filters.equal("name", repositoryName))
      .toFuture()

  def findLatestVersionAtDate(
    repositoryName: String,
    date          : Instant,
    majorVersion  : Option[Int]
  ): Future[Option[MetaArtefact]] =
    collection
      .find(filter = Filters.and(
        Filters.equal("name", repositoryName)
      , Filters.lt("created", date)
      , majorVersion.fold(Filters.empty())(mjv => Filters.regex("version", raw"^$mjv\..*".r))
      ))
      .sort(Sorts.descending("created"))
      .headOption()

  def findLatest(): Future[Seq[MetaArtefact]] =
    collection
      .find(Filters.equal("latest", true))
      .sort(Sorts.ascending("name"))
      .toFuture()
