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
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.{DependencyScopeFlag, ServiceDependencyWrite, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.SlugDenylist
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}

object DerivedMongoCollections {
  val artefactLookup       = "DERIVED-artefact-lookup"
  val slugDependencyLookup = "DERIVED-slug-dependencies"
  val slugDependencyLookupTemp = "DERIVED-slug-dependencies-temp"
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
    // it requires that we move the environment/latest flag out first (e.g. collection join?), or, if the value stays in the same collection, at least maintain the value in a separate process.
    logger.info(s"Running generateSlugDependencyLookup")

    // create temp repo to replace derivedServiceDependenciesRepository - it needs to copy the indices
    // then replace the collection once the temp has finished being populated
    // this emulates the behaviour of the $out aggregation https://docs.mongodb.com/manual/reference/operator/aggregation/out/#replace-existing-collection
    val tempRepo =
      new PlayMongoRepository[ServiceDependencyWrite](
        mongoComponent = mongoComponent,
        collectionName = DerivedMongoCollections.slugDependencyLookupTemp,
        domainFormat   = ServiceDependencyWrite.format,
        indexes        = derivedServiceDependenciesRepository.indexes
      )

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
              include("dependencyDot.build"),
              include("production"),
              include("qa"),
              include("staging"),
              include("development"),
              include("external test"),
              include("integration"),
              include("latest")
            )
          )
        )
      )
      .map { res =>
          logger.info(s"Processing results")
          //val doc = res.toBsonDocument
          val slugName         = res.getString("slugName"   )
          val slugVersion      = res.getString("slugVersion")
          val productionFlag   = res.getBoolean("production"   , false)
          val qaFlag           = res.getBoolean("qa"           , false)
          val stagingFlag      = res.getBoolean("staging"      , false)
          val developmentFlag  = res.getBoolean("development"  , false)
          val externalTestFlag = res.getBoolean("external test", false)
          val integrationFlag  = res.getBoolean("integration"  , false)
          val latestFlag       = res.getBoolean("latest"       , false)

          val dependencyDot: BsonDocument = res.get[BsonDocument]("dependencyDot").getOrElse(BsonDocument())
          val compile = dependencyGraphParser.parse(dependencyDot.getString("compile", BsonString("")).getValue.split("\n")).dependencies.map((_, DependencyScopeFlag.Compile))
          val test    = dependencyGraphParser.parse(dependencyDot.getString("test"   , BsonString("")).getValue.split("\n")).dependencies.map((_, DependencyScopeFlag.Test   ))
          val build   = dependencyGraphParser.parse(dependencyDot.getString("build"  , BsonString("")).getValue.split("\n")).dependencies.map((_, DependencyScopeFlag.Build  ))

          val dependencies: Map[DependencyGraphParser.Node, Set[DependencyScopeFlag]] =
            (compile ++ test ++ build)
              .foldLeft(Map.empty[DependencyGraphParser.Node, Set[DependencyScopeFlag]]){ case (acc, (n, flag)) =>
                acc + (n -> (acc.getOrElse(n, Set.empty) + flag))
              }

          val deps =
            dependencies.map { case (node, scopeFlags) =>
              ServiceDependencyWrite(
                slugName         = slugName,
                slugVersion      = slugVersion,
                depGroup         = node.group,
                depArtefact      = node.artefact,
                depVersion       = node.version,
                scalaVersion     = node.scalaVersion,
                compileFlag      = scopeFlags.contains(DependencyScopeFlag.Compile),
                testFlag         = scopeFlags.contains(DependencyScopeFlag.Test),
                buildFlag        = scopeFlags.contains(DependencyScopeFlag.Build),
                productionFlag   = productionFlag,
                qaFlag           = qaFlag,
                stagingFlag      = stagingFlag,
                developmentFlag  = developmentFlag,
                externalTestFlag = externalTestFlag,
                integrationFlag  = integrationFlag,
                latestFlag       = latestFlag
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
             tempRepo.collection
               .insertMany(group.toSeq).map { _ =>
                 logger.info(s"Inserted ${group.size}")
                 group.size
               }
           }
         )
         // toFuture here is important, since it waits for the observable above to complete, ensuring the collection rename happens at the end
         .toFuture
         .flatMap(res =>
           mongoComponent.client.getDatabase("admin").runCommand(
             BsonDocument(
               "renameCollection" -> ("service-dependencies." + DerivedMongoCollections.slugDependencyLookupTemp),
               "to"               -> ("service-dependencies." + derivedServiceDependenciesRepository.collectionName),
               "dropTarget"       -> true
             )
           ).toFuture.map(_ => ())
         ).map(_ => ())
    }
}
