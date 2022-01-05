/*
 * Copyright 2022 HM Revenue & Customs
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
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.ReplaceOptions
import org.mongodb.scala.model.Aggregates._
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoRepository @Inject()(
  mongoComponent      : MongoComponent,
  deploymentRepository: DeploymentRepository
)(implicit
  ec: ExecutionContext
) extends SlugInfoRepositoryBase[SlugInfo](
  mongoComponent,
  domainFormat   = MongoSlugInfoFormats.slugInfoFormat
) with Logging {

  def add(slugInfo: SlugInfo): Future[Unit] =
    collection
      .replaceOne(
          filter      = equal("uri", slugInfo.uri)
        , replacement = slugInfo
        , options     = ReplaceOptions().upsert(true)
        )
      .toFuture()
      .map(_ => ())

  def getAllEntries: Future[Seq[SlugInfo]] =
    collection.find()
      .toFuture

  def clearAllData: Future[Unit] =
    collection.deleteMany(BsonDocument())
      .toFuture()
      .map(_ => ())

  def getUniqueSlugNames: Future[Seq[String]] =
    collection.distinct[String]("name")
      .toFuture()

 def getSlugInfos(name: String, optVersion: Option[Version]): Future[Seq[SlugInfo]] =
    collection
      .find(
        filter = and(
                   equal("name", name),
                   optVersion.fold[Bson](BsonDocument())(v => equal("version", v.original))
                 )
      )
      .toFuture()

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    findSlugsFromDeployments(
      filter = and(
                 equal("name", name),
                 equal(flag.asString, true)
               )
    ).map(_.headOption)

  def getSlugsForEnv(flag: SlugInfoFlag): Future[Seq[SlugInfo]] =
    findSlugsFromDeployments(
      filter = equal(flag.asString, true)
    )

  private def findSlugsFromDeployments(filter: Bson): Future[Seq[SlugInfo]] =
   deploymentRepository.lookupAgainstDeployments(
      collectionName   = "slugInfos",
      domainFormat     = MongoSlugInfoFormats.slugInfoFormat,
      slugNameField    = "name",
      slugVersionField = "version"
    )(
      deploymentsFilter = filter,
      domainFilter      = BsonDocument()
    )
}
