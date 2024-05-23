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

import cats.implicits._
import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Aggregates.`match`
import org.mongodb.scala.model._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.servicedependencies.model.{GroupArtefacts, MongoSlugInfoFormats, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, SlugDenylist}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedGroupArtefactRepository @Inject()(
  mongoComponent      : MongoComponent
, deploymentRepository: DeploymentRepository
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[GroupArtefacts](
  collectionName = "DERIVED-artefact-lookup",
  mongoComponent = mongoComponent,
  domainFormat   = MongoSlugInfoFormats.groupArtefactsFormat,
  indexes        = Seq.empty,
  optSchema      = None
):

  // we replace all the data for each call to populateAll
  override lazy val requiresTtlIndex = false

  def findGroupsArtefacts(): Future[Seq[GroupArtefacts]] =
    collection
      .find()
      .sort(Sorts.ascending("group"))
      .map(g => g.copy(artefacts = g.artefacts.sorted))
      .toFuture()

  private val groupArtefactsTransformationPipeline: List[Bson] =
    Aggregates.project(
      Projections.fields(
        Projections.excludeId()
        , Projections.include("group", "artefact", "scope_compile", "scope_provided", "scope_test", "scope_it", "scope_build")
      )
    ) ::
      BsonDocument(
        "$group" -> BsonDocument(
            "_id" -> BsonDocument("group" -> "$group")
          , "artifacts" -> BsonDocument("$addToSet" -> "$artefact")
          )
      ) ::
      Aggregates.project( // reproject the result so fields are at the root level
        Projections.fields(
          Projections.computed("group", "$_id.group")
          , Projections.include("artifacts")
          , Projections.exclude("_id")
        )
      ) ::
      Aggregates.addFields(
        Field("scope_compile", true)
      , Field("scope_provided", true)
      , Field("scope_test", true)
      , Field("scope_it", true)
      , Field("scope_build", true)
      ) :: Nil

  private def derivedDeployedDependencies(): Future[Seq[GroupArtefacts]] =
    deploymentRepository.lookupAgainstDeployments(
      collectionName   = "DERIVED-deployed-dependencies"
    , domainFormat     = MongoSlugInfoFormats.groupArtefactsFormat
    , slugNameField    = "repoName"
    , slugVersionField = "repoVersion"
    )(
      deploymentsFilter = Filters.or(SlugInfoFlag.values.map(flag => Filters.equal(flag.asString, true)): _*)
    , domainFilter      = BsonDocument()
    , pipeline          = groupArtefactsTransformationPipeline
    )

  private def derivedLatestDependencies(): Future[Seq[GroupArtefacts]] =
    CollectionFactory
      .collection(mongoComponent.database, "DERIVED-latest-dependencies", domainFormat)
      .aggregate(`match`(Filters.nin("name", SlugDenylist.denylistedSlugs)) :: groupArtefactsTransformationPipeline)
      .toFuture()

  def populateAll(): Future[Unit] =
    for
      deployedDeps   <- derivedDeployedDependencies()
                          .map(_.map(group => (group.group, group.artefacts)).toMap)
      latestDeps     <- derivedLatestDependencies()
                          .map(_.map(group => (group.group, group.artefacts)).toMap)
      groupArtefacts =  deployedDeps
                          .combine(latestDeps)
                          .map { (k, v) => GroupArtefacts(k, v.distinct)}
                          .toSeq
      _              <- collection
                          .bulkWrite:
                            groupArtefacts
                              .map(groupArtefact =>
                                ReplaceOneModel(
                                  filter         = Filters.equal("group", groupArtefact.group)
                                , replacement    = groupArtefact
                                , replaceOptions = ReplaceOptions().upsert(true)
                                )
                              ) :+ DeleteManyModel(filter = Filters.nin("group", groupArtefacts.map(_.group): _*))
                          .toFuture()
    yield ()
