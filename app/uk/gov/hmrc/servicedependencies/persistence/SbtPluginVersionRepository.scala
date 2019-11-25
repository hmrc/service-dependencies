/*
 * Copyright 2019 HM Revenue & Customs
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
import com.mongodb.BasicDBObject
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.hashed
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
import play.api.Logger
import play.api.libs.json.Json
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoCollection
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class SbtPluginVersionRepository @Inject()(mongo: MongoComponent, futureHelpers: FutureHelpers)
    extends PlayMongoCollection[MongoSbtPluginVersion](
      collectionName = "sbtPluginVersions",
      mongoComponent = mongo,
      domainFormat   = MongoSbtPluginVersion.format,
      indexes = Seq(
        IndexModel(hashed("sbtPluginName"), IndexOptions().name("sbtPluginNameIdx").background(true))
      )
    ) {

  val logger: Logger = Logger(this.getClass)

  def update(sbtPluginVersion: MongoSbtPluginVersion): Future[MongoSbtPluginVersion] = {
    logger.debug(s"writing $sbtPluginVersion")
    futureHelpers
      .withTimerAndCounter("mongo.update") {
        collection
          .replaceOne(
            filter = equal("sbtPluginName", sbtPluginVersion.sbtPluginName),
            sbtPluginVersion,
            ReplaceOptions().upsert(true)
          )
          .toFuture()
          .map(_ => sbtPluginVersion)
      }
      .recover {
        case lastError =>
          throw new RuntimeException(s"failed to persist SbtPluginVersion: $sbtPluginVersion", lastError)
      }
  }

  def getAllEntries: Future[Seq[MongoSbtPluginVersion]] = collection.find().toFuture()

  def clearAllData: Future[Boolean] = collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())
}
