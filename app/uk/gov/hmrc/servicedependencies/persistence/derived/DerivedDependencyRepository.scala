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

import org.mongodb.scala.model.{Filters, Indexes, IndexOptions, IndexModel, ReplaceOneModel, ReplaceOptions}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, MetaArtefactDependency, RepoType, Version}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedDependencyRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository[MetaArtefactDependency](
    collectionName = "DERIVED-latest-dependencies"
  , mongoComponent = mongoComponent
  , domainFormat   = MetaArtefactDependency.mongoFormat
  , indexes        = Seq(
                       IndexModel(Indexes.ascending("repoName"), IndexOptions().name("repoNameIdx")),
                       IndexModel(Indexes.ascending("group"),    IndexOptions().name("groupIdx")),
                       IndexModel(Indexes.ascending("artefact"), IndexOptions().name("artefactIdx")),
                       IndexModel(Indexes.ascending("repoType"), IndexOptions().name("repoIdx")),
                     ) ++ DependencyScope.values.map(s => IndexModel(Indexes.ascending(s"scope_${s.asString}"), IndexOptions().name(s"scope_${s.asString}Idx")))
) with Logging {

  // automatically refreshed when given new meta data artefacts from update scheduler
  override lazy val requiresTtlIndex = false

  def find(
    group      : Option[String]                = None,
    artefact   : Option[String]                = None,
    repoType   : Option[List[RepoType]]        = None,
    scopes     : Option[List[DependencyScope]] = None,
    repoName   : Option[String]                = None,
    repoVersion: Option[Version]               = None
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

  def put(dependencies: Seq[MetaArtefactDependency]): Future[Unit] =
    if (dependencies.isEmpty)
      Future.unit
    else
      collection
        .bulkWrite(
          dependencies.map(d =>
            ReplaceOneModel(
              filter = Filters.and(
                Filters.equal("repoName", d.repoName),
                Filters.equal("group",    d.depGroup),
                Filters.equal("artefact", d.depArtefact)
              ),
              replacement    = d,
              replaceOptions = ReplaceOptions().upsert(true)
            )
          )
        ).toFuture()
        .map(_ => ())

  def delete(name: String, version: Option[Version] = None): Future[Unit] =
    collection
      .deleteMany(
        Filters.and(
          Filters.equal("repoName"   , name),
          version.fold(Filters.empty())(v => Filters.equal("repoVersion", v.original))
        )
      ).toFuture()
      .map(_ => ())
}
