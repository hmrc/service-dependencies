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

import javax.inject.Inject
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.{ApiServiceDependencyFormats, ServiceDependency, SlugInfoFlag}

import scala.concurrent.{ExecutionContext, Future}

class DerivedServiceDependenciesRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext
) extends PlayMongoRepository[ServiceDependency](
  collectionName =  DerivedMongoCollections.slugDependencyLookup
  , mongoComponent = mongoComponent
  , domainFormat   = ApiServiceDependencyFormats.derivedMongoFormat
  , indexes        = Seq(
                       IndexModel(
                         ascending("slugName"),
                         IndexOptions().name("derivedServiceDepsSlugNameIdx")
                       ),
                       IndexModel(
                         compoundIndex(
                           ascending("group"),
                           ascending("artefact")
                         ),
                         IndexOptions().name("derivedServiceDepsGroupArtifactIdx")
                       ),
                       IndexModel(
                         compoundIndex(SlugInfoFlag.values.map(f => ascending(f.asString)) :_*),
                         IndexOptions().name("derivedServiceDepsFlagIdx").background(true)
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
  , optSchema = None
){

  def findServicesWithDependency(
    flag    : SlugInfoFlag,
    group   : String,
    artefact: String
  ): Future[Seq[ServiceDependency]] =
    collection.find(and(
      equal(flag.asString, true),
      equal("group", group),
      equal("artefact", artefact))
    ).toFuture()

  def findDependenciesForService(name: String, flag: SlugInfoFlag): Future[Seq[ServiceDependency]] =
    collection.find(
      and(
        equal("slugName", name),
        equal(flag.asString, true),
        equal("compile", true) // TODO make this a parameter
      )
    ).toFuture()
}
