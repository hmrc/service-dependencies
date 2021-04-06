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
import uk.gov.hmrc.servicedependencies.model.{ServiceDependencyWrite, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.SlugDenylist
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}

object DerivedMongoCollections {
  val artefactLookup       = "DERIVED-artefact-lookup"
  val slugDependencyLookup = "DERIVED-slug-dependencies"
}

@Singleton
class DerivedMongoCollections @Inject()(
  mongoComponent       : MongoComponent,
  dependencyGraphParser: DependencyGraphParser
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
    // TODO should we delete the content of the collection, or just run upsert? Can we skip slugs that we've already analysed? (maybe not because of environment tagging?)
    // but we could ignore the environment, and let that be maintained (as we do on slugInfos currently)
    // Better yet, process the dependencyDot off the queue - it's immutable data. We just need to move the environment filtering (which is transient)
    // out into another collection, and make a join?
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
          val compile = dependencyGraphParser.parse(dependencyDot.getString("compile", BsonString("")).getValue.split("\n")).dependencies.map((_, "compile"))
          val test    = dependencyGraphParser.parse(dependencyDot.getString("test"   , BsonString("")).getValue.split("\n")).dependencies.map((_, "test"   ))
          val build   = dependencyGraphParser.parse(dependencyDot.getString("build"  , BsonString("")).getValue.split("\n")).dependencies.map((_, "build"  ))

          val dependencies: Map[DependencyGraphParser.Node, Set[String]] =
            (compile ++ test ++ build)
              .foldLeft(Map.empty[DependencyGraphParser.Node, Set[String]]){ case (acc, (n, flag)) =>
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
                compileFlag      = scopeFlags.contains("compile"),
                testFlag         = scopeFlags.contains("test"   ),
                buildFlag        = scopeFlags.contains("build"  ),
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
             SingleObservable(())
           else {
             logger.info(s"Inserting ${group.size}}")
             import org.mongodb.scala.model.Filters
             targetCollection
               .bulkWrite(
                 group.map(d =>
                  ReplaceOneModel(
                    filter      = Filters.and(
                                    Filters.equal("slugName"   , d.slugName),
                                    Filters.equal("slugVersion", d.slugVersion),
                                    Filters.equal("group"      , d.depGroup),
                                    Filters.equal("artefact"   , d.depArtefact),
                                    Filters.equal("version"    , d.depVersion),
                                  ),
                    replacement = d,
                    replaceOptions = ReplaceOptions().upsert(true)
                  )
                ).toSeq
               ).map { _ =>
                logger.info(s"Inserted ${group.size}")
                ()
               }
           }
         ).toFuture.map(_ => ())
    }
}
