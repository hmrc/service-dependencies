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
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
import play.api.Logger
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
  private val logger = Logger(getClass)

  // not currently supporting no scope - we'd get duplicate groups
  def findGroupsArtefacts(scope: DependencyScope): Future[Seq[GroupArtefacts]] =
    collection
      .find(equal(scope.asString, true))
      .toFuture()

  def populate(): Future[Unit] = {
    logger.info(s"Running DerivedGroupArtefactRepository.populate")
    mongoComponent.database.getCollection("DERIVED-slug-dependencies")
      .aggregate(
        List(
          // Exclude slug internals
          `match`(regex("version", "^(?!.*-assets$)(?!.*-sans-externalized$).*$")),
          project(
            fields(
              excludeId(),
              include("group", "artefact", "compile", "test", "build")
            )
          ),
          BsonDocument("$group" ->
            BsonDocument(
              "_id" ->
                BsonDocument(
                  "group"   -> "$group",
                  "compile" -> "$compile",
                  "test"    -> "$test",
                  "build"   -> "$build"
                ),
              "artifacts" ->
                BsonDocument("$addToSet" -> "$artefact")
            )
          ),
          // reproject the result so fields are at the root level
          project(fields(
            computed("group"  , "$_id.group"),
            computed("compile", "$_id.compile"),
            computed("test"   , "$_id.test"),
            computed("build"  , "$_id.build"),
            include("artifacts"),
            exclude("_id")
          )),
          // replace content of target collection
          out("DERIVED-artefact-lookup")
        )
      )
      .allowDiskUse(true)
      .toFuture
      .map(_ =>
        logger.info(s"Finished running DerivedGroupArtefactRepository.populate")
      )
  }
}
