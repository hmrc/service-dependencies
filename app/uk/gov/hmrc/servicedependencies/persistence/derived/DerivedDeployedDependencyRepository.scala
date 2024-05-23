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

import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes, Filters}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedDeployedDependencyRepository @Inject()(
  mongoComponent       : MongoComponent,
  deploymentRepository : DeploymentRepository
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[MetaArtefactDependency](
  collectionName = "DERIVED-deployed-dependencies"
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
, optSchema      = None
, replaceIndexes = true
):
  // we remove slugs when, artefactProcess detects, they've been deleted from artifactory
  override lazy val requiresTtlIndex = false

  def findWithDeploymentLookup(
    flag    : SlugInfoFlag,
    group   : Option[String]                = None,
    artefact: Option[String]                = None,
    scopes  : Option[List[DependencyScope]] = None,
    slugName: Option[String]                = None,
    slugVersion: Option[Version]            = None
  ): Future[Seq[MetaArtefactDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = Filters.equal(flag.asString, true),
      dependencyFilter  = Seq(
                            group      .map(x  => Filters.equal("group", x)),
                            artefact   .map(x  => Filters.equal("artefact", x)),
                            scopes     .map(xs => Filters.or(xs.map(x => Filters.equal(s"scope_${x.asString}", value = true)): _*)),
                            slugName   .map(x  => Filters.equal("repoName", x)),
                            slugVersion.map(x  => Filters.equal("repoVersion", x.original)),
                          ).flatten
                           .foldLeft(Filters.empty())(Filters.and(_, _))
    )

  def find(slugName: String, slugVersion: Version): Future[Seq[MetaArtefactDependency]] =
    collection
      .find(
        Filters.and(
          Filters.equal("repoName"   , slugName)
        , Filters.equal("repoVersion", slugVersion.toString)
        )
      )
      .toFuture()

  private def findServiceDependenciesFromDeployments(
    deploymentsFilter: Bson,
    dependencyFilter : Bson
  ): Future[Seq[MetaArtefactDependency]] =
    deploymentRepository.lookupAgainstDeployments(
      collectionName   = collectionName,
      domainFormat     = MetaArtefactDependency.mongoFormat,
      slugNameField    = "repoName",
      slugVersionField = "repoVersion"
    )(
      deploymentsFilter = deploymentsFilter,
      domainFilter      = dependencyFilter
    )

  def insert(dependencies: List[MetaArtefactDependency]): Future[Unit] =
    if dependencies.isEmpty then
      Future.unit
    else
      collection
        .insertMany(dependencies)
        .toFuture()
        .map(_ => ())

  def delete(name: String, version: Option[Version] = None, ignoreVersions: Seq[Version] = Nil): Future[Unit] =
    collection
      .deleteMany(
        Filters.and(
          Filters.equal("repoName", name)
        , version.fold(Filters.empty())(v => Filters.equal("repoVersion", v.original))
        , Filters.nin("repoVersion", ignoreVersions.map(_.original): _*)
        )
      )
      .toFuture()
      .map(_ => ())
