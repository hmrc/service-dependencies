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
import com.mongodb.BasicDBObject
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.ReplaceOptions
import org.mongodb.scala.model.Updates._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.throttle.{ThrottleConfig, WithThrottling}
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoRepository @Inject()(
    mongoComponent    : MongoComponent
  , val throttleConfig: ThrottleConfig
  )(implicit ec: ExecutionContext
  ) extends SlugInfoRepositoryBase[SlugInfo](
    mongoComponent
  , domainFormat   = MongoSlugInfoFormats.slugInfoFormat
  ) with WithThrottling {

  def add(slugInfo: SlugInfo): Future[Boolean] =
    collection
      .replaceOne(
          filter      = equal("uri", slugInfo.uri)
        , replacement = slugInfo
        , options   = ReplaceOptions().upsert(true)
        )
      .toThrottledFuture
      .map(_.wasAcknowledged())

  def getAllEntries: Future[Seq[SlugInfo]] =
    collection.find()
      .toThrottledFuture

  def clearAllData: Future[Boolean] =
    collection.deleteMany(new BasicDBObject())
      .toThrottledFuture
      .map(_.wasAcknowledged())

  def getUniqueSlugNames: Future[Seq[String]] =
    collection.distinct[String]("name")
      .toThrottledFuture

 def getSlugInfos(name: String, optVersion: Option[String]): Future[Seq[SlugInfo]] = {
    val filter =
      optVersion match {
        case None          => equal("name", name)
        case Some(version) => and( equal("name"   , name)
                                 , equal("version", version)
                                 )
      }
    collection.find(filter)
      .toThrottledFuture
  }

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    collection
      .find(and(equal("name", name), equal(flag.asString, true)))
      .toThrottledFuture
      .map(_.headOption)

  def getSlugsForEnv(flag: SlugInfoFlag): Future[Seq[SlugInfo]] =
    collection
      .find(equal(flag.asString, true))
      .toThrottledFuture

  def clearFlag(flag: SlugInfoFlag, name: String): Future[Unit] = {
    logger.debug(s"clear ${flag.asString} flag on $name")

    collection
      .updateMany(
          filter = equal("name", name)
        , update = set(flag.asString, false)
        )
      .toThrottledFuture
      .map(_ => ())
  }

  def markLatest(name: String, version: Version): Future[Unit] =
    setFlag(SlugInfoFlag.Latest, name, version)

  def setFlag(flag: SlugInfoFlag, name: String, version: Version): Future[Unit] =
    for {
      _ <- clearFlag(flag, name)
      _ =  logger.debug(s"mark slug $name $version with ${flag.asString} flag")
      _ <- collection
             .updateOne(
                 filter = and( equal("name", name)
                             , equal("version", version.original)
                             )
               , update = set(flag.asString, true))
             .toThrottledFuture
    } yield ()
}
