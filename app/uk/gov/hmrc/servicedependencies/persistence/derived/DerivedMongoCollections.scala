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

package uk.gov.hmrc.servicedependencies.persistence.derived

import javax.inject.Inject
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag
import uk.gov.hmrc.servicedependencies.persistence.SlugBlacklist

import scala.concurrent.{ExecutionContext, Future}

object DerivedMongoCollections {
  val artefactLookup       = "DERIVED-artefact-lookup"
  val slugDependencyLookup = "DERVIED-slug-dependencies"
}

class DerivedMongoCollections @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
  extends Logging {

  def generateArtefactLookup(): Future[Unit] = {
    val agg = List(
      // Pull out any slug infos that are present in any environment (or latest)
      `match`(
        or(
          SlugInfoFlag.values.map(f => equal(f.asString, true)): _* // filter for reachable data
        )
      ),
      // Pull out just the dependencies array
      project(
        fields(
          computed("dependencies", "$dependencies")
        )
      ),
      // Create a new document for each dependency
      unwind("$dependencies"),
      // Exclude slug internals
      `match`(regex("dependencies.version", "^(?!.*-assets$)(?!.*-sans-externalized$).*$")),
      // Group by artefact group id
      group("$dependencies.group", addToSet("artifacts",  "$dependencies.artifact")),
      sort(orderBy(ascending("_id"))),
      out(DerivedMongoCollections.artefactLookup)
    )

    mongoComponent.database.getCollection("slugInfos").aggregate(agg).allowDiskUse(true)
      .toFuture
      .map(_ => Unit)
  }

  /**
    * A flattened version of slugInfo containing only slugs deployed + latest
    * One document per dependency in the slug
    * @return
    */
  def generateSlugDependencyLookup() :Future[Unit] = {
    val agg = List(
      `match`(
        or(
          SlugInfoFlag.values.map(f => equal(f.asString, true)): _* // filter for reachable data
        )
      ),
      `match`(
        nin("name", SlugBlacklist.blacklistedSlugs)
      ),
      project(
        fields(
          excludeId(),
          computed("slugName", "$name"),
          computed("slugVersion", "$version"),
          include("dependencies.group"),
          include("dependencies.artifact"),
          include("dependencies.version"),
          include("production"),
          include("qa"),
          include("staging"),
          include("development"),
          include("external test"),
          include("integration"),
          include("latest")
        )
      ),
      unwind("$dependencies"),
      project(fields(
        computed("group", "$dependencies.group"),
        computed("artifact", "$dependencies.artifact"),
        computed("version", "$dependencies.version"),
        include("slugName"),
        include("slugVersion"),
        include("production"),
        include("qa"),
        include("staging"),
        include("development"),
        include("external test"),
        include("integration"),
        include("latest")
      )),
      out(DerivedMongoCollections.slugDependencyLookup)
    )

    mongoComponent.database.getCollection("slugInfos").aggregate(agg).allowDiskUse(true)
      .toFuture
      .map(_ => Unit)
  }

}
