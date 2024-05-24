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
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, MetaArtefactDependency, RepoType, Version}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedLatestDependencyRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository[MetaArtefactDependency](
  collectionName = "DERIVED-latest-dependencies"
, mongoComponent = mongoComponent
, domainFormat   = MetaArtefactDependency.mongoFormat
, indexes        = IndexModel(
                     Indexes.ascending("repoName", "repoVersion", "group", "artefact", "version"),
                     IndexOptions().name("uniqueIdx").unique(true)
                   ) :: IndexModel(Indexes.ascending("repoName"))
                     :: IndexModel(Indexes.ascending("repoVersion"))
                     :: IndexModel(Indexes.ascending("group"))
                     :: IndexModel(Indexes.ascending("artefact"))
                     :: IndexModel(Indexes.ascending("repoType"))
                     :: DependencyScope.values.map(s => IndexModel(Indexes.hashed("scope_" + s.asString)))
, replaceIndexes = true
) with Transactions with Logging:

  // automatically refreshed when given new meta data artefacts from update scheduler
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration =
    TransactionConfiguration.strict

  def find(
    group      : Option[String]               = None,
    artefact   : Option[String]               = None,
    repoType   : Option[Seq[RepoType]]        = None,
    scopes     : Option[Seq[DependencyScope]] = None,
    repoName   : Option[String]               = None,
    repoVersion: Option[Version]              = None
  ): Future[Seq[MetaArtefactDependency]] =
    collection
      .find(
        Seq(
          group      .map(x  => Filters.equal("group", x)),
          artefact   .map(x  => Filters.equal("artefact", x)),
          repoType   .map(xs => Filters.or(xs.map(x => Filters.equal(s"repoType", x.asString)): _*)),
          scopes     .map(xs => Filters.or(xs.map(x => Filters.equal(s"scope_${x.asString}", value = true)): _*)),
          repoName   .map(x  => Filters.equal("repoName", x)),
          repoVersion.map(x  => Filters.equal("repoVersion", x.original)),
        ).flatten
         .foldLeft(Filters.empty())(Filters.and(_, _))
      ).toFuture()

  def update(repoName: String, dependencies: List[MetaArtefactDependency]): Future[Unit] =
    if dependencies.isEmpty then
      Future.unit
    else if dependencies.exists(_.repoName != repoName) then
      Future.failed(sys.error(s"Repo name: $repoName does not match dependencies ${dependencies.collect { case x if x.repoName != repoName => x.repoName}.mkString(",")}"))
    else
      withSessionAndTransaction: session =>
        for
          _ <- collection.deleteMany(session, Filters.equal("repoName", repoName)).toFuture()
          _ <- collection.insertMany(session, dependencies).toFuture()
        yield ()

  def delete(repoName: String): Future[Unit] =
    collection
      .deleteMany(Filters.equal("repoName", repoName))
      .toFuture()
      .map(_ => ())
