/*
 * Copyright 2022 HM Revenue & Customs
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
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOneModel, ReplaceOptions}
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.servicedependencies.model.{ApiServiceDependencyFormats, DependencyScope, MetaArtefact, ServiceDependency, ServiceDependencyWrite, SlugInfo, SlugInfoFlag, Version}
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

  def findDependencies(
    flag: SlugInfoFlag,
    scope: Option[DependencyScope]
  ): Future[Seq[ServiceDependency]] =
    findServiceDependenciesFromDeployments(
      deploymentsFilter = equal(flag.asString, true),
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

  // use a different collection to register a different format
  private def targetCollection: MongoCollection[ServiceDependencyWrite] =
    CollectionFactory.collection(
      mongoComponent.database,
      DerivedServiceDependenciesRepository.collectionName,
      ServiceDependencyWrite.format
    )

  def populateDependencies(slugInfo: SlugInfo, meta: Option[MetaArtefact]): Future[Unit] = {
    val writes =
      if (meta.isEmpty && slugInfo.dependencyDotCompile.isEmpty) // legacy java slug
            slugInfo.dependencies
              .map(d =>
                ServiceDependencyWrite(
                  slugName         = slugInfo.name,
                  slugVersion      = slugInfo.version,
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
        // java slugs do not have a meta-artefact - need to fall back on slugInfo
        val graphBuild   = meta.flatMap(_.dependencyDotBuild                                ).getOrElse(slugInfo.dependencyDotBuild  )
        val graphCompile = meta.flatMap(_.modules.headOption).flatMap(_.dependencyDotCompile).getOrElse(slugInfo.dependencyDotCompile)
        val graphTest    = meta.flatMap(_.modules.headOption).flatMap(_.dependencyDotTest   ).getOrElse(slugInfo.dependencyDotTest   )

        val build   = dependencyGraphParser.parse(graphBuild  ).dependencies.map((_, DependencyScope.Build  ))
        val compile = dependencyGraphParser.parse(graphCompile).dependencies.map((_, DependencyScope.Compile))
        val test    = dependencyGraphParser.parse(graphTest   ).dependencies.map((_, DependencyScope.Test   ))

        val dependencies: Map[DependencyGraphParser.Node, Set[DependencyScope]] =
          (build ++ compile ++ test)
            .foldLeft(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]]){ case (acc, (n, flag)) =>
              acc + (n -> (acc.getOrElse(n, Set.empty) + flag))
            }

        def toServiceDependencyWrite(group: String, artefact: String, version: Version, scalaVersion: Option[String], scopes: Set[DependencyScope]) =
          ServiceDependencyWrite(
                  slugName         = slugInfo.name,
                  slugVersion      = slugInfo.version,
                  depGroup         = group,
                  depArtefact      = artefact,
                  depVersion       = version,
                  scalaVersion     = scalaVersion,
                  compileFlag      = scopes.contains(DependencyScope.Compile),
                  testFlag         = scopes.contains(DependencyScope.Test),
                  buildFlag        = scopes.contains(DependencyScope.Build)
                )

        val scalaVersion =
          meta.toSeq.flatMap(_.modules).flatMap(_.crossScalaVersions.toSeq.flatten).headOption

        val sbtVersion =
          meta.toSeq.flatMap(_.modules).flatMap(_.sbtVersion).headOption

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
                                 case scope if dependencies.find(dep => dep._2.contains(scope) &&
                                                                        dep._1.group    == group &&
                                                                        dep._1.artefact == artefact
                                                                ).isEmpty => scope
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

        dependencies
          .filter { case (node, _) => node.group != "default"     || node.artefact != "project"     }
          .filter { case (node, _) => node.group != "uk.gov.hmrc" || node.artefact != slugInfo.name }
          .map { case (node, scopes) =>
            toServiceDependencyWrite(
              group        = node.group,
              artefact     = node.artefact,
              version      = Version(node.version),
              scalaVersion = node.scalaVersion,
              scopes       = scopes
            )
          } ++
          dependencyIfMissing(
            group        = "org.scala-lang",
            artefact     = "scala-library",
            version      = scalaVersion,
            scalaVersion = None,
            scopes       = Set(DependencyScope.Compile, DependencyScope.Test)
          ).toList ++
          dependencyIfMissing(
            group        = "org.scala-sbt",
            artefact     = "sbt",
            version      = sbtVersion,
            scalaVersion = None,
            scopes       = Set(DependencyScope.Build)
          ).toList
      }

    if (writes.isEmpty)
      Future.unit
    else {
      logger.info(s"Inserting ${writes.size} dependencies for ${slugInfo.name} ${slugInfo.version}")
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

object DerivedServiceDependenciesRepository {
  val collectionName = "DERIVED-slug-dependencies"
}
