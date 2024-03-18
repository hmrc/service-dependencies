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

package uk.gov.hmrc.servicedependencies.persistence.derived

import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes, Filters, ReplaceOneModel, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedServiceDependenciesRepository @Inject()(
  mongoComponent       : MongoComponent,
  deploymentRepository : DeploymentRepository
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[MetaArtefactDependency](
  collectionName = "DERIVED-slug-dependencies"
, mongoComponent = mongoComponent
, domainFormat   = MetaArtefactDependency.mongoFormat
, indexes        = Seq(
                     IndexModel(
                       Indexes.compoundIndex(
                         Indexes.ascending("slugName"),
                         Indexes.ascending("slugVersion")
                       ),
                       IndexOptions().name("slugName_slugVersion_idx").background(true)
                     ),
                     IndexModel(
                       Indexes.compoundIndex(
                         Indexes.ascending("group"),
                         Indexes.ascending("artefact")
                       ),
                       IndexOptions().name("group_artefact_idx").background(true)
                     ),
                     IndexModel(
                       Indexes.compoundIndex(DependencyScope.values.map(s => Indexes.ascending("scope_" + s.asString)) :_*),
                       IndexOptions().name("dependencyScope_idx").background(true)
                     ),
                     IndexModel(
                       Indexes.compoundIndex(
                         Indexes.ascending("slugName"),
                         Indexes.ascending("slugVersion"),
                         Indexes.ascending("group"),
                         Indexes.ascending("artefact"),
                         Indexes.ascending("version")
                       ),
                       IndexOptions().name("uniqueIdx").unique(true).background(true)
                     )
                   )
, optSchema      = None
, replaceIndexes = true
){
  // we remove slugs when, artefactProcess detects, they've been deleted from artifactory
  override lazy val requiresTtlIndex = false

  def find(
    flag    : SlugInfoFlag,
    group   : Option[String]                = None,
    artefact: Option[String]                = None,
    scopes  : Option[List[DependencyScope]] = None
  ): Future[Seq[MetaArtefactDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = Filters.equal(flag.asString, true),
      dependencyFilter  = Seq(
                            group   .map(x  => Filters.equal("group", x)),
                            artefact.map(x  => Filters.equal("artefact", x)),
                            scopes  .map(xs => Filters.or(xs.map(x => Filters.equal(s"scope_${x.asString}", value = true)): _*))
                          ).flatten
                           .foldLeft(Filters.empty())(Filters.and(_, _))
    )

  private def findServiceDependenciesFromDeployments(
    deploymentsFilter: Bson,
    dependencyFilter : Bson
  ): Future[Seq[MetaArtefactDependency]] =
    deploymentRepository.lookupAgainstDeployments(
      collectionName   = collectionName,
      domainFormat     = MetaArtefactDependency.mongoFormat,
      slugNameField    = "slugName",
      slugVersionField = "slugVersion"
    )(
      deploymentsFilter = deploymentsFilter,
      domainFilter      = dependencyFilter
    )

  def put(dependencies: Seq[MetaArtefactDependency]): Future[Unit] =
    if (dependencies.isEmpty)
      Future.unit
    else {
      collection
        .bulkWrite(
          dependencies.map(d =>
            ReplaceOneModel(
              filter         = Filters.and(
                                 Filters.equal("repoName"   , d.repoName),
                                 Filters.equal("repoVersion", d.repoVersion.original),
                                 Filters.equal("group"      , d.depGroup),
                                 Filters.equal("artefact"   , d.depArtefact),
                                //  Filters.equal("version"    , d.depVersion.original),
                               ),
              replacement    = d,
              replaceOptions = ReplaceOptions().upsert(true)
            )
          ).toSeq
        ).toFuture()
        .map(_ => ())
    }

  def delete(name: String, version: Version): Future[Unit] =
    collection
      .deleteMany(
          Filters.and(
            Filters.equal("repoName"   , name),
            Filters.equal("repoVersion", version.toString)
          )
        )
      .toFuture()
      .map(_ => ())
}
