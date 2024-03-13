/*
 * Copyright 2023 HM Revenue & Customs
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

import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOneModel, ReplaceOptions}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository
import uk.gov.hmrc.servicedependencies.service.DependencyService
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** A flattened version of slugInfo.dependencies for efficient usage.
  * One document per dependency in the slug
  */
@Singleton
class DerivedServiceDependenciesRepository @Inject()(
  mongoComponent       : MongoComponent,
  deploymentRepository : DeploymentRepository
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[ServiceDependency](
  collectionName = "DERIVED-slug-dependencies"
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
, replaceIndexes = true
){
  private val logger = Logger(getClass)

  // we remove slugs when, artefactProcess detects, they've been deleted from artifactory
  override lazy val requiresTtlIndex = false

  def findServicesWithDependency(
    flag    : SlugInfoFlag,
    group   : String,
    artefact: String,
    scopes  : Option[List[DependencyScope]]
  ): Future[Seq[ServiceDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = equal(flag.asString, true),
      dependencyFilter  = and(
                            equal("group", group),
                            equal("artefact", artefact),
                            scopes.fold[Bson](BsonDocument())(ss => or( ss.map(scope => equal(s"scope_${scope.asString}", value = true)): _*))
                          )
    )

  def findDependencies(
    flag: SlugInfoFlag,
    scopes: Option[List[DependencyScope]]
  ): Future[Seq[ServiceDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = equal(flag.asString, true),
      dependencyFilter = scopes.fold[Bson](BsonDocument())(ss => or(ss.map(scope => equal(s"scope_${scope.asString}", value = true)): _*))
    )

  private def findServiceDependenciesFromDeployments(
    deploymentsFilter: Bson,
    dependencyFilter : Bson
  ): Future[Seq[ServiceDependency]] =
    deploymentRepository.lookupAgainstDeployments(
      collectionName   = collectionName,
      domainFormat     = ApiServiceDependencyFormats.derivedMongoFormat,
      slugNameField    = "slugName",
      slugVersionField = "slugVersion"
    )(
      deploymentsFilter = deploymentsFilter,
      domainFilter      = dependencyFilter
    )

  // use a different collection to register a different format
  private def targetCollection: MongoCollection[ServiceDependencyWrite] =
    CollectionFactory.collection(
      mongoComponent.database,
      collectionName,
      ServiceDependencyWrite.format
    )

  def delete(slugName: String, slugVersion: Version): Future[Unit] =
    collection
      .deleteOne(
          and(
            equal("slugName"   , slugName),
            equal("slugVersion", slugVersion.toString)
          )
        )
      .toFuture()
      .map(_ => ())

  def populateDependencies(meta: MetaArtefact): Future[Unit] = {
    logger.info(s"Processing ${meta.name} ${meta.version}")

    def toServiceDependencyWrite(group: String, artefact: String, version: Version, scalaVersion: Option[String], scopes: Set[DependencyScope]) =
      ServiceDependencyWrite(
        slugName     = meta.name,
        slugVersion  = meta.version,
        depGroup     = group,
        depArtefact  = artefact,
        depVersion   = version,
        scalaVersion = scalaVersion,
        compileFlag  = scopes.contains(DependencyScope.Compile),
        providedFlag = scopes.contains(DependencyScope.Provided),
        testFlag     = scopes.contains(DependencyScope.Test),
        itFlag       = scopes.contains(DependencyScope.It),
        buildFlag    = scopes.contains(DependencyScope.Build)
      )

    val scalaVersion =
      meta.modules.flatMap(_.crossScalaVersions.toSeq.flatten).headOption

    val sbtVersion =
      meta.modules.flatMap(_.sbtVersion).headOption

    val dependencies: Map[DependencyGraphParser.Node, Set[DependencyScope]] = DependencyService.parseArtefactDependencies(meta)

    def dependencyIfMissing(
      group       : String,
      artefact    : String,
      version     : Option[Version],
      scalaVersion: Option[String],
      scopes      : Set[DependencyScope]
    ): Option[ServiceDependencyWrite] =
      for {
        v                <- version
        applicableScopes = scopes.collect {
                              case scope if !dependencies.exists(dep =>
                                dep._2.contains(scope) &&
                                dep._1.group == group &&
                                dep._1.artefact == artefact) => scope
                            }
        if applicableScopes.nonEmpty
      } yield
        toServiceDependencyWrite(
          group        = group,
          artefact     = artefact,
          version      = v,
          scalaVersion = scalaVersion,
          scopes       = applicableScopes
        )

    val writes =
      dependencies
        .filterNot { case (node, _) => node.group == "default"     && node.artefact == "project"     }
        .filterNot { case (node, _) => node.group == "uk.gov.hmrc" && node.artefact == meta.name }
        .map { case (node, scopes) =>
          toServiceDependencyWrite(
            group        = node.group,
            artefact     = node.artefact,
            version      = Version(node.version),
            scalaVersion = node.scalaVersion,
            scopes       = scopes
          )
        } ++
        scalaVersion.flatMap(_ => dependencyIfMissing(
          group        = "org.scala-lang",
          artefact     = "scala-library",
          version      = scalaVersion,
          scalaVersion = None,
          scopes       = Set(DependencyScope.Compile, DependencyScope.Test)
        )).toList ++
        sbtVersion.flatMap(_ => dependencyIfMissing(
          group        = "org.scala-sbt",
          artefact     = "sbt",
          version      = sbtVersion,
          scalaVersion = None,
          scopes       = Set(DependencyScope.Build)
        )).toList

    if (writes.isEmpty)
      Future.unit
    else {
      logger.info(s"Inserting ${writes.size} dependencies for ${meta.name} ${meta.version}")
      targetCollection
        .bulkWrite(
          writes.map(d =>
            ReplaceOneModel(
              filter         = and(
                                  equal("slugName"   , d.slugName),
                                  equal("slugVersion", d.slugVersion.original),
                                  equal("group"      , d.depGroup),
                                  equal("artefact"   , d.depArtefact),
                                  equal("version"    , d.depVersion.original),
                                ),
              replacement    = d,
              replaceOptions = ReplaceOptions().upsert(true)
            )
          ).toSeq
        ).toFuture()
        .map(_ => ())
    }
  }
}
