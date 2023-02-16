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

import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, Projections, ReplaceOptions}
import org.mongodb.scala.model.Aggregates.{`match`, project, unwind}
import org.mongodb.scala.model.Filters.{and, equal, exists, or}
import org.mongodb.scala.model.Projections.{excludeId, fields, include}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, Version}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import cats.data.OptionT

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

  def add(metaArtefact: MetaArtefact): Future[Unit] =
    collection
      .replaceOne(
          filter      = and(
                          equal("name"   , metaArtefact.name),
                          equal("version", metaArtefact.version.toString)
                        )
        , replacement = metaArtefact
        , options     = ReplaceOptions().upsert(true)
        )
      .toFuture()
      .map(_ => ())

  def delete(repositoryName: String, version: Version): Future[Unit] =
    collection
      .deleteOne(
          and(
            equal("name"   , repositoryName),
            equal("version", version.toString)
          )
        )
      .toFuture()
      .map(_ => ())

  def find(repositoryName: String): Future[Option[MetaArtefact]] =
    for {
      version <- mongoComponent.database.getCollection("metaArtefacts")
        .find(equal("name", repositoryName))
        .projection(Projections.include("version"))
        .map(bson => Version(bson.getString("version")))
        .filter(_.isReleaseCandidate == false)
        .foldLeft(Version("0.0.0"))((prev,cur) => if(cur > prev) cur else prev)
        .toFuture()
      meta <- find(repositoryName, version)
    } yield meta

  def find(repositoryName: String, version: Version): Future[Option[MetaArtefact]] =
    collection.find(
      filter = and(
                 equal("name"   , repositoryName),
                 equal("version", version.toString)
               )
      )
      .headOption()

  def findAllVersions(repositoryName: String): Future[Seq[MetaArtefact]] =
    collection.find(
      filter = and(
        equal("name", repositoryName)
      )
    ).toFuture()

  // TODO we could store this data normalised to apply an index etc.
  def findRepoNameByModule(group: String, artefact: String, version: Version): Future[Option[String]] =
    OptionT(findRepoNameByModule2(group, artefact, Some(version)))
      // in-case the version predates collecting meta-data, just ignore Version
      .orElse(OptionT(findRepoNameByModule2(group, artefact, None)))
      .value

  private def findRepoNameByModule2(group: String, artefact: String, version: Option[Version]): Future[Option[String]] =
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
              version.fold[Bson](BsonDocument())(v => equal("version", v.toString))
            )
          )
        )
      )
      .headOption()
      .map(_.flatMap(_.get[BsonString]("name")).map(_.getValue))

  def clearAllData: Future[Unit] =
    collection.deleteMany(BsonDocument())
      .toFuture()
      .map(_ => ())
}
