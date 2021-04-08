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
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedGroupArtefactRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[GroupArtefacts](
  collectionName = "DERIVED-artefact-lookup",
  mongoComponent = mongoComponent,
  domainFormat   = MongoSlugInfoFormats.groupArtefactsFormat,
  indexes        = Seq.empty,
  optSchema      = None
){
  def populate(): Future[Unit] =
    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
          // Pull out any slug infos that are present in any environment (or latest)
          `match`(
            or(
              SlugInfoFlag.values.map(f => equal(f.asString, true)): _* // filter for reachable data
            )
          ),
          // Pull out just the dependencies array
          project(
            fields(
              computed("dependencies", "$dependencies")
            )
          ),
          // Create a new document for each dependency
          unwind("$dependencies"),
          // Exclude slug internals
          `match`(regex("dependencies.version", "^(?!.*-assets$)(?!.*-sans-externalized$).*$")),
          // Group by artefact group id
          group("$dependencies.group", addToSet("artifacts",  "$dependencies.artifact")),
          sort(orderBy(ascending("_id"))),
          // replace content of target collection
          out("DERIVED-artefact-lookup")
        )
      )
      .allowDiskUse(true)
      .toFuture
      .map(_ => Unit)

  def findGroupsArtefacts: Future[Seq[GroupArtefacts]] =
    collection.find().toFuture()
}
