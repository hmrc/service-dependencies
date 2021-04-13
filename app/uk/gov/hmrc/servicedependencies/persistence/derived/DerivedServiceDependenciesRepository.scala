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
                         IndexOptions().name("slugName_idx").background(true)
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

  /** Populates the target collection with the data from dependencyDot.
    * This can be run regularly to keep the collection up-to-date.
    */
  def populate(): Future[Unit] =
    // TODO we should process the dependencyDot off the queue - it's immutable data.
    for {
      _                <- Future.successful(logger.info(s"Running DerivedServiceDependenciesRepository.populate"))
      // collect the processed slugs to avoid re-processing them
      processedSlugs   <- mongoComponent.database.getCollection(DerivedServiceDependenciesRepository.collectionName)
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

      // use a different collection to register a different format
      targetCollection =  CollectionFactory.collection(
                            mongoComponent.database,
                            DerivedServiceDependenciesRepository.collectionName,
                            ServiceDependencyWrite.format
                          )
      res              <- mongoComponent.database.getCollection("slugInfos")
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
                               logger.info(s"Found ${dependencies.size} for $slugName $slugVersion")
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
      _             =  logger.info(s"Finished running DerivedServiceDependenciesRepository.populate - added ${res.sum}")
    } yield ()
}

object DerivedServiceDependenciesRepository {
  val collectionName = "DERIVED-slug-dependencies"
}
