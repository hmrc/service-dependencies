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

import cats.implicits._
import javax.inject.{Inject, Singleton}
import org.mongodb.scala.{MongoCollection, SingleObservable}
import org.mongodb.scala.bson.{BsonArray, BsonDocument, BsonString}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOneModel, ReplaceOptions}
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import org.mongodb.scala.model.Projections._
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.servicedependencies.model.{ApiServiceDependencyFormats, DependencyScope, ServiceDependency, ServiceDependencyWrite, SlugInfo, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}

/** A flattened version of slugInfo.dependencies for efficient usage.
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
                         compoundIndex(
                           ascending("slugName"),
                           ascending("slugVersion")
                         ),
                         IndexOptions().name("slugName_slugVersion_idx").background(true)
                       ),
                       IndexModel(
                         compoundIndex(
                           ascending("group"),
                           ascending("artefact")
                         ),
                         IndexOptions().name("group_artefact_idx").background(true)
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
                         IndexOptions().name("uniqueIdx").unique(true).background(true)
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

    // collect the processed slugs to avoid re-processing them
    def findProcessedSlugs(): Future[Seq[(String, String)]] = {
      logger.info(s"Running DerivedServiceDependenciesRepository.findProcessedSlugs")
      mongoComponent.database.getCollection(DerivedServiceDependenciesRepository.collectionName)
        .aggregate(
          List(
            group(
              BsonDocument(
                "slugName"    -> "$slugName",
                "slugVersion" -> "$slugVersion"
              )
            ),
            replaceRoot("$_id")
          )
        )
        .map(res => (res.getString("slugName"), res.getString("slugVersion")))
        .toFuture()
        .map { res =>
          logger.info(s"Finished running DerivedServiceDependenciesRepository.findProcessedSlugs")
          res
        }
      }

  // use a different collection to register a different format
  private def targetCollection: MongoCollection[ServiceDependencyWrite] =
    CollectionFactory.collection(
      mongoComponent.database,
      DerivedServiceDependenciesRepository.collectionName,
      ServiceDependencyWrite.format
    )

  def populateDependencies(slugInfo: SlugInfo): Future[Unit] = {
    val writes =
      if (slugInfo.dependencyDotCompile.isEmpty) // legacy java slug
            slugInfo.dependencies
              .map(d =>
                ServiceDependencyWrite(
                  slugName         = slugInfo.name,
                  slugVersion      = slugInfo.version.toString,
                  depGroup         = d.group,
                  depArtefact      = d.artifact,
                  depVersion       = d.version,
                  scalaVersion     = None,
                  compileFlag      = true,
                  testFlag         = false,
                  buildFlag        = false
                )
              )
      else {
        val compile = dependencyGraphParser.parse(slugInfo.dependencyDotCompile).dependencies.map((_, DependencyScope.Compile))
        val test    = dependencyGraphParser.parse(slugInfo.dependencyDotTest   ).dependencies.map((_, DependencyScope.Test   ))
        val build   = dependencyGraphParser.parse(slugInfo.dependencyDotBuild  ).dependencies.map((_, DependencyScope.Build  ))

        val dependencies: Map[DependencyGraphParser.Node, Set[DependencyScope]] =
          (compile ++ test ++ build)
            .foldLeft(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]]){ case (acc, (n, flag)) =>
              acc + (n -> (acc.getOrElse(n, Set.empty) + flag))
            }

        dependencies
          .filter { case (node, _) => node.group != "default" || node.artefact != "project" }
          .filter { case (node, _) => node.group != "uk.gov.hmrc" || node.artefact != slugInfo.name }
          .map { case (node, scopes) =>
            ServiceDependencyWrite(
              slugName         = slugInfo.name,
              slugVersion      = slugInfo.version.toString,
              depGroup         = node.group,
              depArtefact      = node.artefact,
              depVersion       = node.version,
              scalaVersion     = node.scalaVersion,
              compileFlag      = scopes.contains(DependencyScope.Compile),
              testFlag         = scopes.contains(DependencyScope.Test),
              buildFlag        = scopes.contains(DependencyScope.Build)
            )
          }
      }

    if (writes.isEmpty)
      Future.successful(())
    else {
      logger.info(s"Inserting ${writes.size} dependencies for ${slugInfo.name} ${slugInfo.version}")
      targetCollection
        .bulkWrite(
          writes.map(d =>
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
        ).toFuture()
        .map(_ => ())
    }
  }

  /** Populates the target collection with the data from dependencyDot.
    * This can be run regularly to keep the collection up-to-date.
    */
  def populate(processedSlugs: Seq[(String, String)]): Future[Unit] = {
    // TODO we should process the dependencyDot off the queue - it's immutable data.
    logger.info(s"Running DerivedServiceDependenciesRepository.populate")
    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
          // just to make the execution quicker - the filter can be removed if we parse all data off queue
          project(
            fields(
              BsonDocument("name_version" -> BsonDocument("$concat" -> BsonArray("$name", "_", "$version"))),
              include("name", "version", "dependencyDot")
            )
          ),
          `match`(
            and(
              exists("dependencyDot", true),
              // this feeds a VERY large list of slugs (82k) in
              // we should be able to replace all of this once we process off the queue directly, rather than on a scheduler.
              nin("name_version"   , processedSlugs.map(a => a._1 + "_" + a._2):_ *)
            )
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
          val compile = dependencyGraphParser.parse(dependencyDot.getString("compile", BsonString("")).getValue).dependencies.map((_, DependencyScope.Compile))
          val test    = dependencyGraphParser.parse(dependencyDot.getString("test"   , BsonString("")).getValue).dependencies.map((_, DependencyScope.Test   ))
          val build   = dependencyGraphParser.parse(dependencyDot.getString("build"  , BsonString("")).getValue).dependencies.map((_, DependencyScope.Build  ))

          val dependencies: Map[DependencyGraphParser.Node, Set[DependencyScope]] =
            (compile ++ test ++ build)
              .foldLeft(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]]){ case (acc, (n, flag)) =>
                acc + (n -> (acc.getOrElse(n, Set.empty) + flag))
              }

          val deps =
            dependencies
              .filter { case (node, _) => node.group != "default" || node.artefact != "project" }
              .filter { case (node, _) => node.group != "uk.gov.hmrc" || node.artefact != slugName }
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
          logger.info(s"Found ${dependencies.size} dependencies for $slugName $slugVersion")
          deps
        }
        .flatMap(group =>
          if (group.isEmpty)
            SingleObservable(0)
          else {
            logger.info(s"Inserting ${group.size} dependencies")
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
        .map(res => logger.info(s"Finished running DerivedServiceDependenciesRepository.populate - added ${res.sum}"))
  }


  /** Java slugs do not have dependencyDot data available yet. Pull from dependencies field. */
  def populateLegacy(processedSlugs: Seq[(String, String)]): Future[Unit] = {
    logger.info(s"Running DerivedServiceDependenciesRepository.populateLegacy")
    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
          // just to make the execution quicker - the filter can be removed if we parse all data off queue
          project(
            fields(
              BsonDocument("name_version" -> BsonDocument("$concat" -> BsonArray("$name", "_", "$version"))),
              include("name", "version", "dependencyDot", "dependencies")
            )
          ),
          `match`(
            and(
              exists("dependencyDot", false),
              // this feeds a VERY large list of slugs (82k) in
              // we should be able to replace all of this once we process off the queue directly, rather than on a scheduler.
              nin("name_version"   , processedSlugs.map(a => a._1 + "_" + a._2):_ *)
            )
          ),
          // project relevant fields including the dependencies list
          project(
            fields(
              excludeId(),
              computed("slugName", "$name"),
              computed("slugVersion", "$version"),
              include("dependencies.group"),
              include("dependencies.artifact"),
              include("dependencies.version"),
            )
          ),
          // unwind the dependencies into 1 record per dependency
          unwind("$dependencies"),
          // reproject the result so dependencies are at the root level
          project(
            fields(
              computed("group", "$dependencies.group"),
              computed("artefact", "$dependencies.artifact"),
              computed("version", "$dependencies.version"),
              include("slugName"),
              include("slugVersion")
            )
          )
        )
      )
      .allowDiskUse(true)
      .toFuture()
      .flatMap { res =>
        logger.info(s"Found ${res.size} legacy dependencies")
        res
        .grouped(100)
        .toList
        .traverse(group =>
          targetCollection
            .bulkWrite(
              group
                .map { res =>
                  val slugName    = res.getString("slugName")
                  val slugVersion = res.getString("slugVersion")
                  val group       = res.getString("group")
                  val artefact    = res.getString("artefact")
                  val version     = res.getString("version")
                  ReplaceOneModel(
                            filter         = and(
                                                equal("slugName"   , slugName),
                                                equal("slugVersion", slugVersion),
                                                equal("group"      , group),
                                                equal("artefact"   , artefact),
                                                equal("version"    , version),
                                              ),
                            replacement    = ServiceDependencyWrite(
                                                slugName         = slugName,
                                                slugVersion      = slugVersion,
                                                depGroup         = group,
                                                depArtefact      = artefact,
                                                depVersion       = version,
                                                scalaVersion     = None,
                                                compileFlag      = true,
                                                testFlag         = false,
                                                buildFlag        = false
                                              ),
                            replaceOptions = ReplaceOptions().upsert(true)
                          )
                }
            )
            .toFuture
        )
      }
      .map(_ => logger.info(s"Finished running DerivedServiceDependenciesRepository.populateLegacy"))
  }
}

object DerivedServiceDependenciesRepository {
  val collectionName = "DERIVED-slug-dependencies"
}
