/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.presistence

import play.api.libs.json.Json
import reactivemongo.api.DB
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.util.FutureHelpers.withTimerAndCounter
import uk.gov.hmrc.servicedependencies.model._

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{ExecutionContext, Future}



trait SbtPluginVersionRepository {

  def update(sbtPluginVersion: MongoSbtPluginVersion): Future[MongoSbtPluginVersion]
  def getAllEntries: Future[Seq[MongoSbtPluginVersion]]
  def clearAllData: Future[Boolean]
}

class MongoSbtPluginVersionRepository(mongo: () => DB)
  extends ReactiveRepository[MongoSbtPluginVersion, BSONObjectID](
    collectionName = "sbtPluginVersions",
    mongo = mongo,
    domainFormat = MongoSbtPluginVersion.format) with SbtPluginVersionRepository {


  override def ensureIndexes(implicit ec: ExecutionContext = defaultContext): Future[Seq[Boolean]] =
    localEnsureIndexes

  private def localEnsureIndexes =
    Future.sequence(
      Seq(
        collection.indexesManager(defaultContext).ensure(Index(Seq("sbtPluginName" -> IndexType.Hashed), name = Some("sbtPluginNameIdx"), unique = true))
      )
    )

  override def update(sbtPluginVersion: MongoSbtPluginVersion): Future[MongoSbtPluginVersion] = {



    logger.info(s"writing $sbtPluginVersion")
    withTimerAndCounter("mongo.update") {
      for {
        update <- collection.update(selector = Json.obj("sbtPluginName" -> Json.toJson(sbtPluginVersion.sbtPluginName)), update = sbtPluginVersion, upsert = true)
      } yield update match {
        case lastError if lastError.inError => throw new RuntimeException(s"failed to persist SbtPluginVersion: $sbtPluginVersion")
        case _ => sbtPluginVersion
      }
    }
  }

  override def getAllEntries: Future[Seq[MongoSbtPluginVersion]] = findAll()

  override def clearAllData: Future[Boolean] = super.removeAll().map(!_.hasErrors)
}

