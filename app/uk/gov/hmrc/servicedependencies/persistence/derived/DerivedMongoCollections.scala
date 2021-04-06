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
import org.mongodb.scala.bson.{BsonBoolean, BsonDocument, BsonString}
import org.mongodb.scala.model.Accumulators._
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.model.Sorts._
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.CollectionFactory
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag
import uk.gov.hmrc.servicedependencies.persistence.SlugDenylist
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}

object DerivedMongoCollections {
  val artefactLookup       = "DERIVED-artefact-lookup"
  val slugDependencyLookup = "DERIVED-slug-dependencies"
  val dependencyDotLookup  = "DERIVED-dependency-dot"
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
  def generateSlugDependencyLookup(): Future[Unit] =
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
              include("dependencies.group"),
              include("dependencies.artifact"),
              include("dependencies.version"),
              include("production"),
              include("qa"),
              include("staging"),
              include("development"),
              include("external test"),
              include("integration"),
              include("latest")
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
              include("slugVersion"),
              include("production"),
              include("qa"),
              include("staging"),
              include("development"),
              include("external test"),
              include("integration"),
              include("latest")
            )
          ),
          // replace content of target collection
          out(DerivedMongoCollections.slugDependencyLookup)
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
  def generateDependencyDotLookup(): Future[Unit] = {
    // TODO should we delete the content of the collection, or just run upsert? Can we skip slugs that we've already analysed? (maybe not because of environment tagging?)
    // but we could ignore the environment, and let that be maintained (as we do on slugInfos currently)
    val targetCollection =
      CollectionFactory.collection(mongoComponent.database, DerivedMongoCollections.dependencyDotLookup, Dependency.format)
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
      // TODO stream the result out of collection
      .toFuture
      .flatMap(
        _.flatMap { res =>
          val doc = res.toBsonDocument

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
              Dependency(
                slugName         = res.getString("slugName"   ),
                slugVersion      = res.getString("slugVersion"),
                version          = node.version,
                group            = node.group,
                artefact         = node.artefact,
                scalaVersion     = node.scalaVersion,
                compileFlag      = scopeFlags.contains("compile"),
                testFlag         = scopeFlags.contains("test"   ),
                buildFlag        = scopeFlags.contains("build"  ),
                productionFlag   = res.getBoolean("production"   , false),
                qaFlag           = res.getBoolean("qa"           , false),
                stagingFlag      = res.getBoolean("staging"      , false),
                developmentFlag  = res.getBoolean("development"  , false),
                externalTestFlag = res.getBoolean("external test", false),
                integrationFlag  = res.getBoolean("integration"  , false),
                latestFlag       = res.getBoolean("latest"       , false)
              )
            }
          logger.info(s"Found ${dependencies.size} for ${res.getString("slugName")}")
          deps
        }.grouped(100) // TODO what's a good group size?
         .toList.traverse_ { group =>
           logger.info(s"Inserting ${group.size}}")
           if (group.isEmpty)
             Future.successful(())
           else
             targetCollection
               .insertMany(group).toFuture
         }
      )
    }

  // TODO can this replace ServiceDependency?

//   case class ServiceDependency(
//     slugName    : String,
//     slugVersion : String,
//     teams       : List[String],
//     depGroup    : String,
//     depArtefact : String,
//     depVersion  : String) {
//   lazy val depSemanticVersion: Option[Version] =
//     Version.parse(depVersion)
// }

// do we still need SlugDependency? i.e. should we be returning it with SlugInfo?
//   case class SlugDependency(
//   path       : String,
//   version    : String,
//   group      : String,
//   artifact   : String,
//   meta       : String = ""
// )

  case class Dependency(
    slugName        : String,
    slugVersion     : String,
    version         : String,
    group           : String,
    artefact        : String,
    scalaVersion    : Option[String],
    // scope flag
    compileFlag     : Boolean,
    testFlag        : Boolean,
    buildFlag       : Boolean,
    // env flags
    productionFlag  : Boolean,
    qaFlag          : Boolean,
    stagingFlag     : Boolean,
    developmentFlag : Boolean,
    externalTestFlag: Boolean,
    integrationFlag : Boolean,
    latestFlag      : Boolean
  )

  object Dependency {
    import play.api.libs.functional.syntax._
    import play.api.libs.json.{OFormat, __}
    val format: OFormat[Dependency] =
      ( (__ \ "slugName"     ).format[String]
      ~ (__ \ "slugVersion"  ).format[String]
      ~ (__ \ "version"      ).format[String]
      ~ (__ \ "group"        ).format[String]
      ~ (__ \ "artifact"     ).format[String]
      ~ (__ \ "scalaVersion" ).formatNullable[String]
      ~ (__ \ "compile"      ).format[Boolean]
      ~ (__ \ "test"         ).format[Boolean]
      ~ (__ \ "build"        ).format[Boolean]
      ~ (__ \ "production"   ).format[Boolean]
      ~ (__ \ "qa"           ).format[Boolean]
      ~ (__ \ "staging"      ).format[Boolean]
      ~ (__ \ "development"  ).format[Boolean]
      ~ (__ \ "external test").format[Boolean] // TODO confirm space
      ~ (__ \ "integration"  ).format[Boolean]
      ~ (__ \ "latest"       ).format[Boolean]
      )(Dependency.apply _, unlift(Dependency.unapply _))
  }
}
