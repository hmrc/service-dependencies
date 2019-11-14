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
import org.mongodb.scala.model.Aggregates.{`match`, project}
import org.mongodb.scala.model.Filters.{and, equal, nin, notEqual}
import org.mongodb.scala.model.Indexes.{ascending, hashed}
import org.mongodb.scala.model.Projections.{computed, fields}
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import play.api.Logger
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.play.PlayMongoCollection
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class JdkVersionRepository @Inject()(mongo: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoCollection[JDKVersion](
      collectionName = "slugInfos",
      mongoComponent = mongo,
      domainFormat   = MongoSlugInfoFormats.jdkVersionFormat,
      indexes = Seq(
        IndexModel(ascending("uri"), IndexOptions().name("slugInfoUniqueIdx").unique(true)),
        IndexModel(hashed("name"), IndexOptions().name("slugInfoIdx").background(true)),
        IndexModel(hashed("latest"), IndexOptions().name("slugInfoLatestIdx").background(true))
      )
    ) {

  val logger: Logger = Logger(this.getClass)

  def findJDKUsage(flag: SlugInfoFlag): Future[Seq[JDKVersion]] = {
    val agg = List(
      `match`(
        and(
          equal(flag.asString, true),
          notEqual("java.version", ""),
          nin("name", SlugBlacklist.blacklistedSlugs)
        )
      ),
      project(
        fields(
          computed("name", "$name"),
          computed("version", f"$$java.version"),
          computed("vendor", f"$$java.vendor"),
          computed("kind", f"$$java.kind")
        ))
    )
    collection.aggregate(agg).toFuture()
  }
}
