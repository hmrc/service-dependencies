/*
 * Copyright 2020 HM Revenue & Customs
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

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Indexes.hashed
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.throttle.{ThrottleConfig, WithThrottling}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LibraryVersionRepository @Inject()(
    mongoComponent: MongoComponent
  , futureHelpers : FutureHelpers
  , throttleConfig: ThrottleConfig
  )(implicit ec: ExecutionContext
  ) extends DependencyVersionRepository(
    collectionName = "libraryVersions"
  , mongoComponent = mongoComponent
  , futureHelpers = futureHelpers
  , throttleConfig = throttleConfig
  )

@Singleton
class SbtPluginVersionRepository @Inject()(
    mongoComponent: MongoComponent
  , futureHelpers : FutureHelpers
  , throttleConfig: ThrottleConfig
  )(implicit ec: ExecutionContext
  ) extends DependencyVersionRepository(
    collectionName = "sbtPluginVersions"
  , mongoComponent = mongoComponent
  , futureHelpers = futureHelpers
  , throttleConfig = throttleConfig
  )

// TODO Should we just store sbtPluginVersions and libraryVersions all together? also otherDependencies?
class DependencyVersionRepository @Inject()(
    collectionName    : String
  , mongoComponent    : MongoComponent
  , futureHelpers     : FutureHelpers
  , val throttleConfig: ThrottleConfig
  )(implicit ec: ExecutionContext
  ) extends PlayMongoRepository[MongoDependencyVersion](
    collectionName = collectionName
  , mongoComponent = mongoComponent
  , domainFormat   = MongoDependencyVersion.format
  , indexes        = Seq(
                       IndexModel(hashed("name"), IndexOptions().name("nameIdx").background(true))
                     )
  , optSchema      = Some(BsonDocument(MongoDependencyVersion.schema))
  ) with WithThrottling {

  val logger: Logger = Logger(this.getClass)

  def update(dependencyVersion: MongoDependencyVersion): Future[Unit] = {
    logger.debug(s"writing $dependencyVersion")
    futureHelpers
      .withTimerAndCounter("mongo.update") {
        collection
          .replaceOne(
              filter      = and( equal("name" , dependencyVersion.name)
                               , equal("group", dependencyVersion.group)
                               )
            , replacement = dependencyVersion
            , options     = ReplaceOptions().upsert(true)
            )
          .toThrottledFuture
          .map(_ => ())
    } recover {
      case e =>
        throw new RuntimeException(s"failed to persist dependency version $dependencyVersion in $collectionName: ${e.getMessage}", e)
    }
  }

  def getAllEntries: Future[Seq[MongoDependencyVersion]] =
    collection.find()
      .toThrottledFuture

  def clearAllData: Future[Boolean] =
    collection.deleteMany(BsonDocument())
      .toThrottledFuture
      .map(_.wasAcknowledged())
}
