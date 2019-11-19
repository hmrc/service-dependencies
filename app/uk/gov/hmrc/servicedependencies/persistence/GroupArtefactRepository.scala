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
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GroupArtefactRepository @Inject()(mongo: MongoComponent)(implicit ec: ExecutionContext)
    extends SlugInfoRepositoryBase[GroupArtefacts](
      mongo,
      domainFormat   = MongoSlugInfoFormats.groupArtefactsFormat
    ) {

  def findGroupsArtefacts: Future[Seq[GroupArtefacts]] = {
    val agg = List(
      `match`(
        or(
          SlugInfoFlag.values.map(f => equal(f.asString, true)): _* // filter for reachable data
        )
      ),
      project(
        fields(
          computed("dependencies", "$dependencies")
        )
      ),
      unwind("dependencies"),
      `match`(regex("dependencies.version", "^(?!.*-assets$)(?!.*-sans-externalized$).*$")),  // exclude slug internals
      group("$dependencies.group", addToSet("artifacts",  "dependencies.artifact")),
      sort(orderBy(ascending("_id")))
    )

    collection.aggregate(agg).allowDiskUse(true).toFuture()

  }

}