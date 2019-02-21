/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.persistence

import com.google.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONDocumentReader, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model.{GroupArtefacts, MongoSlugInfoFormats, ServiceDependency, SlugInfo}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[SlugInfo, BSONObjectID](
    collectionName = "slugInfos",
    mongo          = mongo.mongoConnector.db,
    domainFormat   = MongoSlugInfoFormats.siFormat){

  import MongoSlugInfoFormats._
  import ExecutionContext.Implicits.global

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("uri" -> IndexType.Ascending),
        name   = Some("slugInfoUniqueIdx"),
        unique = true),
      Index(
        Seq("name" -> IndexType.Hashed),
        name       = Some("slugInfoIdx"),
        background = true))

  def add(slugInfo: SlugInfo): Future[Boolean] =
    collection
      .update(
        selector = Json.obj("uri" -> Json.toJson(slugInfo.uri)),
        update   = slugInfo,
        upsert   = true)
      .map(_.ok)

  def getAllEntries: Future[Seq[SlugInfo]] =
    findAll()

  def clearAllData: Future[Boolean] =
    super.removeAll().map(_.ok)

  def getSlugInfos(name: String, optVersion: Option[String]): Future[Seq[SlugInfo]] =
    optVersion match {
      case None          => find("name" -> name)
      case Some(version) => find("name" -> name, "version" -> version)
    }


  private val readerServiceDependency = new BSONDocumentReader[ServiceDependency]{
    override def read(bson: BSONDocument): ServiceDependency = {
      val opt: Option[ServiceDependency] = for {
        slugName     <- bson.getAs[String]("slugName")
        slugVersion  <- bson.getAs[String]("slugVersion")
        teams        <- bson.getAs[List[String]]("teams").orElse(Some(List.empty))
        depGroup     <- bson.getAs[String]("depGroup")
        depArtifact  <- bson.getAs[String]("depArtifact")
        depVersion   <- bson.getAs[String]("depVersion")
      } yield ServiceDependency(
          slugName    = slugName
        , slugVersion = slugVersion
        , teams       = teams
        , depGroup    = depGroup
        , depArtefact = depArtifact
        , depVersion  = depVersion
        )
      opt.get
    }
  }

  def findServices(group: String, artefact: String): Future[Seq[ServiceDependency]] = {
    val col: BSONCollection = mongo.mongoConnector.db().collection(collectionName)

    import col.BatchCommands.AggregationFramework.{Ascending, Descending, FirstField, Group, Match, Project, Sort, UnwindField}
    import reactivemongo.bson._

    implicit val rsd = readerServiceDependency

    // pipeline functions
    val sortBySlug = Sort(Ascending("name"), Descending("versionLong"))

    val groupByLatestSlug = Group(BSONString(f"$$name"))(
        "version"      -> FirstField("version")
      , "uri"          -> FirstField("uri")
      , "dependencies" -> FirstField("dependencies")
      , "versionLong"  -> FirstField("versionLong")
      )


    val unwindDependencies = UnwindField("dependencies")


    val projectIntoServiceDependency = Project(
      document(
         "_id"          -> 0
        , "slugName"    -> "$_id"
        , "slugVersion" -> "$version"
        , "versionLong" -> "$versionLong"
        , "lib"         -> "$dependencies"
        , "depGroup"    -> "$dependencies.group"
        , "depArtifact" -> "$dependencies.artifact"
        , "depVersion"  -> "$dependencies.version"
        )
    )

    val matchArtifact = Match(document("dependencies.artifact" -> artefact, "dependencies.group" -> group))

    // run the pipeline
    col.aggregatorContext[ServiceDependency](
        sortBySlug
      , List(
            groupByLatestSlug
          , unwindDependencies
          , matchArtifact
          , projectIntoServiceDependency
          )
      , allowDiskUse = true
      )
      .prepared
      .cursor
      .collect[List](-1, Cursor.FailOnError[List[ServiceDependency]]())
  }

  private val readerGroupArtefacts = new BSONDocumentReader[GroupArtefacts]{
    override def read(bson: BSONDocument): GroupArtefacts = {
      val opt: Option[GroupArtefacts] = for {
        group     <- bson.getAs[String      ]("_id")
        artefacts <- bson.getAs[List[String]]("artifacts")
      } yield GroupArtefacts(
          group     = group
        , artefacts = artefacts
        )
      opt.getOrElse(sys.error(s"Could not read bson ${bson.elements.toList}"))
    }
  }

  def findGroupsArtefacts: Future[Seq[GroupArtefacts]] = {
    val col: BSONCollection = mongo.mongoConnector.db().collection(collectionName)
    import col.BatchCommands.AggregationFramework.{AddFieldToSet, Ascending, Group, Project, Sort, UnwindField}
    import reactivemongo.bson._
    implicit val rga = readerGroupArtefacts

    col.aggregatorContext[GroupArtefacts](
      Project(
        document("dependencies" -> "$dependencies")
      )
      , List(
          UnwindField("dependencies")
        , Group(BSONString("$dependencies.group"))("artifacts" -> AddFieldToSet("dependencies.artifact"))
        , Sort(Ascending("_id"))
        )
      , allowDiskUse = true
      )
      .prepared
      .cursor
      .collect[List](-1, Cursor.FailOnError[List[GroupArtefacts]]())
  }
}