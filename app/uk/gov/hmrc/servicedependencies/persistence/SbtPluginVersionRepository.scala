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
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SbtPluginVersionRepository @Inject()(mongo: ReactiveMongoComponent, futureHelpers: FutureHelpers)
    extends ReactiveRepository[MongoSbtPluginVersion, BSONObjectID](
      collectionName = "sbtPluginVersions",
      mongo          = mongo.mongoConnector.db,
      domainFormat   = MongoSbtPluginVersion.format) {

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("sbtPluginName" -> IndexType.Hashed),
        name       = Some("sbtPluginNameIdx"),
        background = true))

  def update(sbtPluginVersion: MongoSbtPluginVersion): Future[MongoSbtPluginVersion] = {
    logger.debug(s"writing $sbtPluginVersion")
    futureHelpers.withTimerAndCounter("mongo.update") {
      collection.update(
          selector = Json.obj("sbtPluginName" -> Json.toJson(sbtPluginVersion.sbtPluginName)),
          update   = sbtPluginVersion,
          upsert   = true)
        .map(_ => sbtPluginVersion)
    }.recover {
      case lastError => throw new RuntimeException(s"failed to persist SbtPluginVersion: $sbtPluginVersion", lastError)
    }
  }

  def getAllEntries: Future[Seq[MongoSbtPluginVersion]] = findAll()

  def clearAllData: Future[Boolean] = super.removeAll().map(_.ok)
}
