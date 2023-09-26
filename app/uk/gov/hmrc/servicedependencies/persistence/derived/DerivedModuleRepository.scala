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

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Aggregates, Filters, Indexes, IndexModel, Projections}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, MetaArtefactModule, Version}

import scala.concurrent.{ExecutionContext, Future}

// Flattens meta artefact's module field so that we can looks up meta artefact by module name, with an index
@Singleton
class DerivedModuleRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[DerivedModule](
    mongoComponent = mongoComponent
  , collectionName = "DERIVED-modules"
  , domainFormat   = DerivedModule.mongoFormat
  , indexes        = IndexModel(Indexes.ascending("moduleGroup", "moduleName", "version" )) ::
                     IndexModel(Indexes.ascending("name", "version")) ::
                     Nil
  , replaceIndexes = true
) with Transactions {

  // we replace all the data for each call to populateAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  import cats.data.OptionT
  def findNameByModule(group: String, artefact: String, version: Version): Future[Option[String]] =
    OptionT(findNameByModule2(group, artefact, Some(version)))
      .orElse(OptionT(findNameByModule2(group, artefact, None))) // in-case the version predates collecting meta-data, just ignore Version
      .value

  private def findNameByModule2(group: String, module: String, version: Option[Version]): Future[Option[String]] =
    collection
      .find(
          Filters.and(
            Filters.equal("moduleGroup", group)
          , Filters.equal("moduleName" , module)
          , version.fold(Filters.empty())(v => Filters.equal("version", version.toString))
          )
        )
      .limit(1) // Since version is optional
      .toFuture()
      .map(_.headOption.map(_.name))

  def delete(name: String, version: Version): Future[Unit] =
    collection
      .deleteMany(
          Filters.and(
            Filters.equal("name"   , name)
          , Filters.equal("version", version.toString)
          )
        )
      .toFuture()
      .map(_ => ())

  def add(metaArtefact: MetaArtefact): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection
               .deleteMany(
                 session
               , Filters.and(Filters.equal("name", metaArtefact.name), Filters.equal("version", metaArtefact.version.toString))
               ).toFuture()
        _ <- collection
               .insertMany(
                 session
               , metaArtefact.modules.map(m => DerivedModule.toDerivedModule(metaArtefact, m))
               ).toFuture()
      } yield ()
    }

  def populateAll(): Future[Unit] =
    mongoComponent.database.getCollection("metaArtefacts")
      .aggregate(
        List(
          Aggregates.project(Projections.fields(Projections.excludeId(), Projections.include("name", "version", "modules.group", "modules.name")))
        , Aggregates.unwind("$modules")
        , Aggregates.project(BsonDocument(
            "moduleGroup" -> "$modules.group"
          , "moduleName"  -> "$modules.name"
          , "name"        -> "$name"
          , "version"     -> "$version"
          ))
        , Aggregates.out("DERIVED-modules") // replace content of target collection
        )
      )
      .allowDiskUse(true)
      .toFuture()
      .map(_ => ())

}

case class DerivedModule(moduleGroup: String, moduleName: String, name: String, version: Version)

import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}

object DerivedModule {

  def toDerivedModule(meta: MetaArtefact, module: MetaArtefactModule) = DerivedModule(
    moduleGroup = module.group
  , moduleName  = module.name
  , name        = meta.name
  , version     = meta.version
  )

  val mongoFormat: Format[DerivedModule] =
    ( (__ \ "moduleGroup").format[String]
    ~ (__ \ "moduleName" ).format[String]
    ~ (__ \ "name"       ).format[String]
    ~ (__ \ "version"    ).format[Version](Version.format)
    )(DerivedModule.apply, unlift(DerivedModule.unapply))
}
