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

import com.google.inject.Inject
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, ReplaceOneModel, ReplaceOptions}
import org.mongodb.scala.model.Filters.equal
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.MetaArtefactDependency

import scala.concurrent.{ExecutionContext, Future}

class DerivedDependencyRepository @Inject()(
                                      mongoComponent: MongoComponent
                                    )(implicit ec: ExecutionContext
                                    ) extends PlayMongoRepository[MetaArtefactDependency](
  collectionName = "DERIVED-dependencies"
  , mongoComponent = mongoComponent
  , domainFormat   = MetaArtefactDependency.format
  , indexes        = Seq(
    IndexModel(Indexes.ascending("slugName"), IndexOptions().name("slugNameIdx"))
  )
) with Logging {

  override lazy val requiresTtlIndex = false

  def add(dependencies: Seq[MetaArtefactDependency]): Future[Unit] = collection
    .insertMany(dependencies)
    .toFuture()
    .map(_ => ())

  def addAndReplace(dependencies: Seq[MetaArtefactDependency]): Future[Unit] = {
    collection
        .bulkWrite(
          dependencies.map(d =>
            ReplaceOneModel(
              filter = Filters.and(
                equal("slugName",     d.slugName),
                equal("group",        d.group),
                equal("artefact",     d.artefact)
              ),
              replacement = d,
              replaceOptions = ReplaceOptions().upsert(true)
            )
          )
        ).toFuture()
        .map(_ => ())
  }

  def find(slugName: Option[String], group: Option[String], artifact: Option[String]): Future[Seq[MetaArtefactDependency]] = {

    val nameFilter: Option[Bson]      = slugName.map(slugName => equal("slugName", slugName))
    val groupFilter: Option[Bson]     = group.map(group => equal("group", group))
    val artifactFilter: Option[Bson]  = artifact.map(artifact => equal("artifact", artifact))

    val filters = Seq(nameFilter).flatten

    collection
      .find(if (filters.isEmpty) BsonDocument() else Filters.and(filters: _*))
      .toFuture()
  }

}
