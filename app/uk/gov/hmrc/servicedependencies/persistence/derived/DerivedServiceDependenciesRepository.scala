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
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOneModel, ReplaceOptions, Field}
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import org.mongodb.scala.model.Projections._
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.servicedependencies.model.{ApiServiceDependencyFormats, DependencyScope, ServiceDependency, ServiceDependencyWrite, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}

/** A flattened version of slugInfo containing only slugs deployed + latest
  * One document per dependency in the slug
  */
@Singleton
class DerivedServiceDependenciesRepository @Inject()(
  mongoComponent       : MongoComponent,
  dependencyGraphParser: DependencyGraphParser,
  deploymentRepository : DeploymentRepository
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[ServiceDependency](
    collectionName = DerivedServiceDependenciesRepository.collectionName
  , mongoComponent = mongoComponent
  , domainFormat   = ApiServiceDependencyFormats.derivedMongoFormat
  , indexes        = Seq(
                       IndexModel(
                         ascending("slugName"),
                         IndexOptions().name("slugName_idx")
                       ),
                       IndexModel(
                         compoundIndex(
                           ascending("group"),
                           ascending("artefact")
                         ),
                         IndexOptions().name("group_artefact_idx")
                       ),
                       IndexModel(
                         compoundIndex(DependencyScope.values.map(s => ascending("scope_" + s.asString)) :_*),
                         IndexOptions().name("dependencyScope_idx").background(true)
                       ),
                       IndexModel(
                         compoundIndex(
                           ascending("slugName"),
                           ascending("slugVersion"),
                           ascending("group"),
                           ascending("artefact"),
                           ascending("version")
                         ),
                         IndexOptions().name("uniqueIdx").unique(true)
                       )
                     )
  , optSchema      = None
){
  private val logger = Logger(getClass)

  def findServicesWithDependency(
    flag    : SlugInfoFlag,
    group   : String,
    artefact: String,
    scope   : Option[DependencyScope]
  ): Future[Seq[ServiceDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = equal(flag.asString, true),
      dependencyFilter  = and(
                            equal("group", group),
                            equal("artefact", artefact),
                            scope.fold[Bson](BsonDocument())(s => equal("scope_" + s.asString, true))
                          )
    )

  def findDependenciesForService(
    name : String,
    flag : SlugInfoFlag,
    scope: Option[DependencyScope]
  ): Future[Seq[ServiceDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = and(
                            equal("slugName", name),
                            equal(flag.asString, true)
                          ),
      dependencyFilter  = scope.fold[Bson](BsonDocument())(s => equal("scope_" + s.asString, true))
    )

  private def findServiceDependenciesFromDeployments(
    deploymentsFilter: Bson,
    dependencyFilter : Bson
  ): Future[Seq[ServiceDependency]] =
    deploymentRepository.lookupAgainstDeployments(
      collectionName   = DerivedServiceDependenciesRepository.collectionName,
      domainFormat     = ApiServiceDependencyFormats.derivedMongoFormat,
      slugNameField    = "slugName",
      slugVersionField = "slugVersion"
    )(
      deploymentsFilter = deploymentsFilter,
      domainFilter      = dependencyFilter
    )

  /** Populates the target collection with the data from dependencyDot.
    * This can be run regularly to keep the collection up-to-date.
    */
  def populate(): Future[Unit] = {
    // TODO we should process the dependencyDot off the queue - it's immutable data.
    logger.info(s"Running DerivedServiceDependenciesRepository.populate")

    // use a different collection to register a different format
    val targetCollection =
      CollectionFactory.collection(
        mongoComponent.database,
        DerivedServiceDependenciesRepository.collectionName,
        ServiceDependencyWrite.format
      )

/*

 mongoComponent.database.getCollection(DerivedServiceDependenciesRepository.collectionName)
      .aggregate(
        List(
          lookup(
            from     = "slugInfos",
            let      = Seq(
                         Variable("sn", "$name"),
                         Variable("sv", "$version")
                       ),
            pipeline = List(
                         `match`(
                           and(
                             expr(
                               and(
                                 // can't use Filters.eq which strips the $eq out, and thus complains about $name/$version not being operators
                                 BsonDocument("$eq" -> BsonArray("$name"   , "$$sn")),
                                 BsonDocument("$eq" -> BsonArray("$version", "$$sv")),
                                 // Exclude slug internals
                                 `match`(regex("version", "^(?!.*-assets$)(?!.*-sans-externalized$).*$"))
                               )
                             ),
                             domainFilter
                           )
                         )
                       ),
            as       = "res"
          ),
          unwind("$res"),
          replaceRoot("$res"),
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
                     filter         = and(
                                        equal("slugName"   , d.slugName),
                                        equal("slugVersion", d.slugVersion),
                                        equal("group"      , d.depGroup),
                                        equal("artefact"   , d.depArtefact),
                                        equal("version"    , d.depVersion),
                                      ),
                     replacement    = d,
                     replaceOptions = ReplaceOptions().upsert(true)
                   )
                  ).toSeq
               ).map(_ => group.size)
           }
         )
         .toFuture()
         .map(res =>
           logger.info(s"Finished running DerivedServiceDependenciesRepository.populate - added ${res.sum}")
         )
    }


*/




    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
          // TODO filter by those that are not already in DERIVED-slug-dependencies (just to make the execution quicker - this can be removed if we parse all data off queue)
          `match`(
            exists("dependencyDot", true)
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
            dependencies
              .filter { case (node, _) => node.group != "default" || node.artefact != "project" }
              .map { case (node, scopes) =>
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
                     filter         = and(
                                        equal("slugName"   , d.slugName),
                                        equal("slugVersion", d.slugVersion),
                                        equal("group"      , d.depGroup),
                                        equal("artefact"   , d.depArtefact),
                                        equal("version"    , d.depVersion),
                                      ),
                     replacement    = d,
                     replaceOptions = ReplaceOptions().upsert(true)
                   )
                  ).toSeq
               ).map(_ => group.size)
           }
         )
         .toFuture()
         .map(res =>
           logger.info(s"Finished running DerivedServiceDependenciesRepository.populate - added ${res.sum}")
         )
    }


  /** Populates the target collection with the data from the dependencies field.
    * This is legacy, and will wipes the current target collection data. `populate` will need to be run afterwards.
    * It should only need to be called once to regenerate the collection, since all new data should have dependencyDot data.
    */
  def populateLegacy() :Future[Unit] = {
    logger.info(s"Running DerivedServiceDependenciesRepository.populateLegacy")
    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
          `match`(
            exists("dependencyDot", false)
          ),
          // project relevant fields including the dependencies list
          project(
            fields(
              excludeId(),
              computed("slugName"   , "$name"),
              computed("slugVersion", "$version"),
              include("dependencies.group"),
              include("dependencies.artifact"),
              include("dependencies.version")
            )
          ),
          // unwind the dependencies into 1 record per depdencies
          unwind("$dependencies"),
          // dedupe (some dependencies are included twice)
          BsonDocument("$group" ->
            BsonDocument("_id" ->
              BsonDocument(
                "slugName"    -> "$slugName",
                "slugVersion" -> "$slugVersion",
                "group"       -> "$dependencies.group",
                "artefact"    -> "$dependencies.artifact",
                "version"     -> "$dependencies.version"
              )
            )
          ),
          /*group(
            BsonDocument(
              "slugName"    -> "$slugName",
              "slugVersion" -> "$slugVersion",
              "group"       -> "$group",
              "artefact"    -> "$artifact",
              "version"     -> "$version"
            ),
            addToSet("_id", "$_id")),*/
          replaceRoot("$_id"),
          addFields(
            Field("scope_compile", true ),
            Field("scope_test"   , false),
            Field("scope_build"  , false)
          ),
          // replace content of target collection
          out(DerivedServiceDependenciesRepository.collectionName)
        )
      )
      .allowDiskUse(true)
      .toFuture()
      .map(_ =>
        logger.info(s"Finished running DerivedServiceDependenciesRepository.populateLegacy")
      )
  }

  /** Populates the collection with dependencies not in dependencyDot format (legacy data).
    * Note, this only needs to be run once.
    * @return
    */
  /*def populateLegacy(): Future[Unit] =
    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
          `match`(
            exists("dependencyDot", false)
          ),
          // project relevant fields including the dependencies list
          project(
            fields(
              excludeId(),
              computed("slugName", "$name"),
              computed("slugVersion", "$version"),
              include("dependencies.group"),
              include("dependencies.artifact"),
              include("dependencies.version")
            )
          ),
          // unwind the dependencies into 1 record per depdencies
          unwind("$dependencies"),
          // reproject the result so dependencies are at the root level
          project(fields(
            computed("group", "$dependencies.group"),
            computed("artefact", "$dependencies.artifact"),
            computed("version", "$dependencies.version"),
            include("slugName"),
            include("slugVersion")
          ))
        )
      )
      .map { res =>
        val slugName    = res.getString("slugName"   )
        val slugVersion = res.getString("slugVersion")
        val group       = res.getString("group")
        val artefact    = res.getString("artefact")
        val version     = res.getString("version")

        ServiceDependencyWrite(
                slugName         = slugName,
                slugVersion      = slugVersion,
                depGroup         = group,
                depArtefact      = artefact,
                depVersion       = version,
                scalaVersion     = None, // can we get this?
                compileFlag      = true,
                testFlag         = false,
                buildFlag        = false
              )
       }
       // TODO bulk write... It's very slow!
       .map(d =>
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
      )
      .toFuture
      .map(_ => ())
      */
}

object DerivedServiceDependenciesRepository {
  val collectionName = "DERIVED-slug-dependencies"
}
