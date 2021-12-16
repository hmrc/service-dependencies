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

package uk.gov.hmrc.servicedependencies.persistence

import org.mongodb.scala.bson.BsonString
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import org.mongodb.scala.model.Aggregates.{`match`, project, unwind}
import org.mongodb.scala.model.Filters.{and, equal, exists, or}
import org.mongodb.scala.model.Projections.{fields, excludeId, include}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, Version}


import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetaArtefactRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[MetaArtefact](
  collectionName = "metaArtefacts",
  mongoComponent = mongoComponent,
  domainFormat   = MetaArtefact.mongoFormat,
  indexes        = Seq(IndexModel(Indexes.ascending("name", "version"), IndexOptions().unique(true)))
) with Logging {

  def insert(metaArtefact: MetaArtefact): Future[Unit] =
    collection.insertOne(metaArtefact)
      .toFuture
      .map(_ => ())

  def find(repositoryName: String): Future[Option[MetaArtefact]] =
    collection.find(equal("name", repositoryName))
      .toFuture()
      .map(
        _
          .filterNot(_.version.isReleaseCandidate)
          .sortBy(_.version)
          .headOption
      )

  // TODO we could store this data normalised to apply an index etc.
  def findRepoNameByModule(group: String, artefact: String, version: Version): Future[Option[String]] =
     mongoComponent.database.getCollection("metaArtefacts")
      .aggregate(
        List(
          project(
            fields(
              excludeId(),
              include("version"),
              include("name"),
              include("modules.group"),
              include("modules.name")
            )
          ),
          unwind("$modules"),
          `match`(
            and(
              or(
                exists("modules.group", false),
                equal("modules.group", group)
              ),
              equal("modules.name", artefact),
              equal("version"     , version.toString)
            )
          )
        )
      )
      .headOption
      .map(_.flatMap(_.get[BsonString]("name")).map(_.getValue))
}
