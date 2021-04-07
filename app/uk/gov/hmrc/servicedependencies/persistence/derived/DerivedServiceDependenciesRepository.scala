/*
 * Copyright 2021 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.{BsonArray, BsonDocument}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import org.mongodb.scala.model.{IndexModel, IndexOptions, Variable}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.servicedependencies.model.{ApiServiceDependencyFormats, DependencyScope, ServiceDependency, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedServiceDependenciesRepository @Inject()(
  mongoComponent      : MongoComponent,
  deploymentRepository: DeploymentRepository
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[ServiceDependency](
    collectionName = DerivedMongoCollections.slugDependencyLookup
  , mongoComponent = mongoComponent
  , domainFormat   = ApiServiceDependencyFormats.derivedMongoFormat
  , indexes        = Seq( // TODO Indices have changed, will need to drop in order to deploy (we can't use replaceIndexes for index rename)
                       IndexModel(
                         ascending("slugName"),
                         IndexOptions().name("slugNameIdx")
                       ),
                       IndexModel(
                         compoundIndex(
                           ascending("group"),
                           ascending("artefact")
                         ),
                         IndexOptions().name("groupArtefactIdx")
                       ),
                       IndexModel(
                         compoundIndex(SlugInfoFlag.values.map(f => ascending(f.asString)) :_*),
                         IndexOptions().name("slugInfoFlagIdx").background(true)
                       ),
                       IndexModel(
                         compoundIndex(DependencyScope.values.map(f => ascending(f.asString)) :_*),
                         IndexOptions().name("dependencyScopeIdx").background(true)
                       ),
                       IndexModel(
                         compoundIndex(
                           ascending("slugName"),
                           ascending("slugVersion"),
                           ascending("group"),
                           ascending("artefact"),
                           ascending("version")
                         ),
                         IndexOptions().name("uniqueIdx").unique(true)
                       )
                     )
  , optSchema      = None
){

  def findServicesWithDependency(
    flag    : SlugInfoFlag,
    group   : String,
    artefact: String,
    scope   : Option[DependencyScope]
  ): Future[Seq[ServiceDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = equal(flag.asString, true),
      dependencyFilter  = and(
                            equal("group", group),
                            equal("artefact", artefact),
                            scope.fold[Bson](BsonDocument())(s => equal(s.asString, true))
                          )
    )

  def findDependenciesForService(
    name : String,
    flag : SlugInfoFlag,
    scope: Option[DependencyScope]
  ): Future[Seq[ServiceDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = and(
                            equal("slugName", name),
                            equal(flag.asString, true)
                          ),
      dependencyFilter  = scope.fold[Bson](BsonDocument())(sf => equal(sf.asString, true))
    )

  private def findServiceDependenciesFromDeployments(
    deploymentsFilter: Bson,
    dependencyFilter : Bson
  ): Future[Seq[ServiceDependency]] =
    deploymentRepository.lookupAgainstDeployments(
      collectionName   = DerivedMongoCollections.slugDependencyLookup,
      domainFormat     = ApiServiceDependencyFormats.derivedMongoFormat,
      slugNameField    = "slugName",
      slugVersionField = "slugVersion"
    )(
      deploymentsFilter = deploymentsFilter,
      domainFilter      = dependencyFilter
    )
}
