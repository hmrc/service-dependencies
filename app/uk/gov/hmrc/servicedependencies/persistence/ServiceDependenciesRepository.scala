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
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters.{and, _}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.throttle.{ThrottleConfig, WithThrottling}
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceDependenciesRepository @Inject()(
    mongoComponent    : MongoComponent
  , val throttleConfig: ThrottleConfig
  )(implicit ec: ExecutionContext
  ) extends SlugInfoRepositoryBase[ServiceDependency](
    mongoComponent
  , domainFormat   = MongoSlugInfoFormats.serviceDependencyFormat
  ) with WithThrottling {

  def findServices(flag: SlugInfoFlag, group: String, artefact: String): Future[Seq[ServiceDependency]] = {

    val agg = List(
      `match`(
        and(
          equal(flag.asString, true),
          nin("name", SlugBlacklist.blacklistedSlugs)
        )
      ),
      sort(orderBy(ascending("name"))),
      unwind("$dependencies"),
      `match`(and(equal("dependencies.artifact", artefact), equal("dependencies.group", group))),
      project(
        fields(
          excludeId(),
          computed("slugName", "$name"),
          computed("slugVersion", "$version"),
          computed("versionLong", "$versionLong"),
          computed("lib", "$dependencies"),
          computed("depGroup", "$dependencies.group"),
          computed("depArtifact", "$dependencies.artifact"),
          computed("depVersion", "$dependencies.version")
        ))
    )

    collection.aggregate(agg).allowDiskUse(true)
      .toFuture
  }
}
