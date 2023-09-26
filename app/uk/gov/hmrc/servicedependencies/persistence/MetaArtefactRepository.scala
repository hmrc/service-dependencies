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

import org.mongodb.scala.bson.{BsonDocument}
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, Projections, ReplaceOptions}
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

  // we remove meta-artefacts when, artefactProcess detects, they've been deleted from artifactory
  override lazy val requiresTtlIndex = false

  def add(metaArtefact: MetaArtefact): Future[Unit] =
    collection
      .replaceOne(
          filter      = Filters.and(
                          Filters.equal("name"   , metaArtefact.name),
                          Filters.equal("version", metaArtefact.version.toString)
                        )
        , replacement = metaArtefact
        , options     = ReplaceOptions().upsert(true)
        )
      .toFuture()
      .map(_ => ())

  def delete(repositoryName: String, version: Version): Future[Unit] =
    collection
      .deleteOne(
          Filters.and(
            Filters.equal("name"   , repositoryName),
            Filters.equal("version", version.toString)
          )
        )
      .toFuture()
      .map(_ => ())

  def find(repositoryName: String): Future[Option[MetaArtefact]] =
    for {
      version <- mongoComponent.database.getCollection("metaArtefacts")
                   .find(Filters.equal("name", repositoryName))
                   .projection(Projections.include("version"))
                   .map(bson => Version(bson.getString("version")))
                   .filter(_.isReleaseCandidate == false)
                   .foldLeft(Version("0.0.0"))((prev,cur) => if(cur > prev) cur else prev)
                   .toFuture()
      meta    <- find(repositoryName, version)
    } yield meta

  def find(repositoryName: String, version: Version): Future[Option[MetaArtefact]] =
    collection.find(
      filter = Filters.and(
                 Filters.equal("name"   , repositoryName),
                 Filters.equal("version", version.toString)
               )
      )
      .headOption()

  def findAllVersions(repositoryName: String): Future[Seq[MetaArtefact]] =
    collection.find(
      filter = Filters.equal("name", repositoryName)
    ).toFuture()

  def clearAllData(): Future[Unit] =
    collection.deleteMany(BsonDocument())
      .toFuture()
      .map(_ => ())
}
