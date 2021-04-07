/*
 * Copyright 2021 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.SingleObservable
import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.model.{ReplaceOneModel, ReplaceOptions}
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.CollectionFactory
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, ServiceDependencyWrite, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.SlugDenylist
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}

object DerivedMongoCollections {
  val artefactLookup       = "DERIVED-artefact-lookup"
  val slugDependencyLookup = "DERIVED-slug-dependencies"
}

@Singleton
class DerivedMongoCollections @Inject()(
  mongoComponent                      : MongoComponent,
  dependencyGraphParser               : DependencyGraphParser,
  derivedServiceDependenciesRepository: DerivedServiceDependenciesRepository
)(implicit
  ec: ExecutionContext
) extends Logging {

  def generateArtefactLookup(): Future[Unit] =
    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
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
          // replace content of target collection
          out(DerivedMongoCollections.artefactLookup)
        )
      )
      .allowDiskUse(true)
      .toFuture
      .map(_ => Unit)

  /**
    * A flattened version of slugInfo containing only slugs deployed + latest
    * One document per dependency in the slug
    * @return
    */
  def generateSlugDependencyLookup(): Future[Unit] = {
    // TODO we should process the dependencyDot off the queue - it's immutable data.
    logger.info(s"Running generateSlugDependencyLookup")

    val targetCollection =
      CollectionFactory.collection(mongoComponent.database, DerivedMongoCollections.slugDependencyLookup, ServiceDependencyWrite.format)

    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
          // filter slugs to just those deployed in any environment, or tagged as latest
          `match`(
            or(
              SlugInfoFlag.values.map(f => equal(f.asString, true)): _* // filter for reachable data
            )
          ),
          // remove denylisted slugs
          `match`(
            nin("name", SlugDenylist.denylistedSlugs)
          ),
          // project relevant fields including the dependencies list
          project(
            fields(
              excludeId(),
              computed("slugName", "$name"),
              computed("slugVersion", "$version"),
              include("dependencyDot.compile"),
              include("dependencyDot.test"),
              include("dependencyDot.build")
            )
          )
        )
      )
      .map { res =>
          logger.info(s"Processing results")
          val slugName    = res.getString("slugName"   )
          val slugVersion = res.getString("slugVersion")

          val dependencyDot: BsonDocument = res.get[BsonDocument]("dependencyDot").getOrElse(BsonDocument())
          val compile = dependencyGraphParser.parse(dependencyDot.getString("compile", BsonString("")).getValue.split("\n")).dependencies.map((_, DependencyScope.Compile))
          val test    = dependencyGraphParser.parse(dependencyDot.getString("test"   , BsonString("")).getValue.split("\n")).dependencies.map((_, DependencyScope.Test   ))
          val build   = dependencyGraphParser.parse(dependencyDot.getString("build"  , BsonString("")).getValue.split("\n")).dependencies.map((_, DependencyScope.Build  ))

          val dependencies: Map[DependencyGraphParser.Node, Set[DependencyScope]] =
            (compile ++ test ++ build)
              .foldLeft(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]]){ case (acc, (n, flag)) =>
                acc + (n -> (acc.getOrElse(n, Set.empty) + flag))
              }

          val deps =
            dependencies.map { case (node, scopes) =>
              ServiceDependencyWrite(
                slugName         = slugName,
                slugVersion      = slugVersion,
                depGroup         = node.group,
                depArtefact      = node.artefact,
                depVersion       = node.version,
                scalaVersion     = node.scalaVersion,
                compileFlag      = scopes.contains(DependencyScope.Compile),
                testFlag         = scopes.contains(DependencyScope.Test),
                buildFlag        = scopes.contains(DependencyScope.Build)
              )
            }
          logger.info(s"Found ${dependencies.size} for $slugName $slugVersion")
          deps
        }
         .flatMap(group =>
           if (group.isEmpty)
             SingleObservable(0)
           else {
             logger.info(s"Inserting ${group.size}}")
             targetCollection
               .bulkWrite(
                 group.map(d =>
                   ReplaceOneModel(
                     filter      = and(
                                     equal("slugName"   , d.slugName),
                                     equal("slugVersion", d.slugVersion),
                                     equal("group"      , d.depGroup),
                                     equal("artefact"   , d.depArtefact),
                                     equal("version"    , d.depVersion),
                                   ),
                     replacement = d
                   )
                  ).toSeq
               ).map(_ => group.size)
           }
         )
         .toFuture()
         .map { res =>
           logger.info(s"Finished running generateSlugDependencyLookup - added ${res.sum}")
           ()
         }
    }
}
