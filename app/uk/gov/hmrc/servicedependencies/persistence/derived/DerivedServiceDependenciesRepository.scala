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
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOneModel}
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
  , indexes        = Seq( // TODO Indices have changed, will need to drop in order to deploy (we can't use replaceIndexes for index rename)
                       IndexModel(
                         ascending("slugName"),
                         IndexOptions().name("slugNameIdx")
                       ),
                       IndexModel(
                         compoundIndex(
                           ascending("group"),
                           ascending("artefact")
                         ),
                         IndexOptions().name("groupArtefactIdx")
                       ),
                       IndexModel(
                         compoundIndex(DependencyScope.values.map(f => ascending(f.asString)) :_*),
                         IndexOptions().name("dependencyScopeIdx").background(true)
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
                            scope.fold[Bson](BsonDocument())(s => equal(s.asString, true))
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
      dependencyFilter  = scope.fold[Bson](BsonDocument())(sf => equal(sf.asString, true))
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

  /**
    * A flattened version of slugInfo containing only slugs deployed + latest
    * One document per dependency in the slug
    * @return
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

    mongoComponent.database.getCollection("slugInfos")
      .aggregate(
        List(
          // filter slugs to just those deployed in any environment, or tagged as latest (just to make the execution quicker - this can be removed if we parse all data off queue)
          `match`(
            or(
              SlugInfoFlag.values.map(f => equal(f.asString, true)): _* // filter for reachable data
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
           logger.info(s"Finished running DerivedServiceDependenciesRepository.populate - added ${res.sum}")
           ()
         }
    }
}

object DerivedServiceDependenciesRepository {
  val collectionName = "DERIVED-slug-dependencies"
}
