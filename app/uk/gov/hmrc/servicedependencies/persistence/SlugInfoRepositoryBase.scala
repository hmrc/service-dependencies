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
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex, descending, hashed}
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import play.api.libs.json.Format
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.MongoSlugInfoFormats

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

@Singleton
abstract class SlugInfoRepositoryBase[A: ClassTag] @Inject()(
    mongoComponent: MongoComponent
  , domainFormat  : Format[A]
  )(implicit ec: ExecutionContext
  ) extends PlayMongoRepository[A](
    collectionName = "slugInfos"
  , mongoComponent = mongoComponent
  , domainFormat   = domainFormat
  , indexes        = Seq(
                       IndexModel(ascending("uri"), IndexOptions().name("slugInfoUniqueIdx").unique(true)),
                       IndexModel(hashed("name"), IndexOptions().name("slugInfoIdx").background(true)),
                       IndexModel(hashed("latest"), IndexOptions().name("slugInfoLatestIdx").background(true)),
                       IndexModel(compoundIndex(ascending("name"), descending("version")),
                         IndexOptions().name("slugInfoNameVersionIdx").background(true)),
                       IndexModel(compoundIndex(ascending("name"), descending("latest")),
                         IndexOptions().name("slugInfoNameLatestIdx").background(true))
                      )
  , optSchema      = Some(BsonDocument(MongoSlugInfoFormats.schema))
  )