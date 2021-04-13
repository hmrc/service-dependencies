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
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.{Field, Sorts}
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
      .find(equal("scope_" + scope.asString, true))
      .sort(Sorts.ascending("group"))
      .map(g => g.copy(artefacts = g.artefacts.sorted))
      .toFuture()

  def populate(): Future[Unit] = {
    logger.info(s"Running DerivedGroupArtefactRepository.populate")
    mongoComponent.database.getCollection("DERIVED-slug-dependencies")
      .aggregate(
        List(
          project(
            fields(
              excludeId(),
              include("group", "artefact", "scope_compile", "scope_test", "scope_build")
            )
          ),
          BsonDocument("$group" ->
            BsonDocument(
              "_id" ->
                BsonDocument(
                  "group"         -> "$group"//,
                  //"scope_compile" -> "$scope_compile",
                  //"scope_test"    -> "$scope_test",
                  //"scope_build"   -> "$scope_build"
                ),
              "artifacts" ->
                BsonDocument("$addToSet" -> "$artefact")
            )
          ),
          // reproject the result so fields are at the root level
          project(fields(
            computed("group"  , "$_id.group"),
            //computed("scope_compile", "$_id.scope_compile"),
            //computed("scope_test"   , "$_id.scope_test"),
            //computed("scope_build"  , "$_id.scope_build"),
            include("artifacts"),
            exclude("_id")
          )),
          addFields(
            Field("scope_compile", true),
            Field("scope_test", true),
            Field("scope_build", true)
          ),
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
