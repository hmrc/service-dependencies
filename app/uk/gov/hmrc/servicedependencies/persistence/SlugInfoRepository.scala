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
import play.api.libs.json.{JsResult, JsValue, Json, OFormat}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONDocumentReader, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers._
import reactivemongo.play.json.JSONSerializationPack.Reader
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model._

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
        background = true),
      Index(
        Seq("latest" -> IndexType.Hashed),
        name       = Some("slugInfoLatestIdx"),
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

  def getUniqueSlugNames: Future[Seq[String]] =
    collection
      .distinct[String, Seq]("name", None)

  def getSlugInfos(name: String, optVersion: Option[String]): Future[Seq[SlugInfo]] =
    optVersion match {
      case None          => find("name" -> name)
      case Some(version) => find("name" -> name, "version" -> version)
    }

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    find(
      "name" -> name,
      flag.s -> true)
      .map(_.headOption)

  def clearFlag(flag: SlugInfoFlag, name: String): Future[Unit] = {
    logger.info(s"clear ${flag.s} flag on $name")
    collection
      .update(
          selector = Json.obj("name" -> name)
        , update   = Json.obj("$set" -> Json.obj(flag.s -> false))
        , multi    = true
        )
      .map(_ => ())
  }

  def markLatest(name: String, version: Version): Future[Unit] =
    setFlag(SlugInfoFlag.Latest, name, version)

  def setFlag(flag: SlugInfoFlag, name: String, version: Version): Future[Unit] =
    for {
      _ <- clearFlag(flag, name)
      _ =  logger.info(s"mark slug $name $version with ${flag.s} flag")
      _ <- collection
            .update(
                selector = Json.obj( "name"    -> name
                                   , "version" -> version.original
                                   )
              , update   = Json.obj("$set" -> Json.obj(flag.s -> true))
              )
    } yield ()


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
      opt.getOrElse(sys.error(s"Could not extract result from ${bson.elements.toList}"))
    }
  }

  def findServices(flag: SlugInfoFlag, group: String, artefact: String): Future[Seq[ServiceDependency]] = {
    val col: BSONCollection = mongo.mongoConnector.db().collection(collectionName)

    import col.BatchCommands.AggregationFramework.{Ascending, Descending, FirstField, Group, Match, Project, Sort, UnwindField}
    import reactivemongo.bson._

    implicit val rsd = readerServiceDependency

    col.aggregatorContext[ServiceDependency](
        Match(document(
            flag.s -> true
          , "name" -> document("$nin" -> SlugBlacklist.blacklistedSlugs)
          ))
      , List(
            Sort(Ascending("name"))
          , UnwindField("dependencies")
          , Match(
              document( "dependencies.artifact" -> artefact
                      , "dependencies.group"    -> group
                      )
            )
          , Project(
              document( "_id"         -> 0
                      , "slugName"    -> "$name"
                      , "slugVersion" -> "$version"
                      , "versionLong" -> "$versionLong"
                      , "lib"         -> "$dependencies"
                      , "depGroup"    -> "$dependencies.group"
                      , "depArtifact" -> "$dependencies.artifact"
                      , "depVersion"  -> "$dependencies.version"
                      )
            )
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

  def findJDKUsage(flag: SlugInfoFlag) : Future[Seq[JDKVersion]] = {

    val query = BSONDocument(flag.s -> true, "jdkVersion" -> BSONDocument("$ne" -> ""), "name" -> BSONDocument("$nin" -> SlugBlacklist.blacklistedSlugs))
    val projection = BSONDocument("name" -> 1, "jdkVersion" -> 1, "flag" -> flag.s)
    implicit val reads: OFormat[JDKVersion] = JDKVersionFormats.jdkFormat

    collection.find(query, Some(projection))
      .cursor[JDKVersion]()
      .collect(-1, Cursor.FailOnError[List[JDKVersion]]())
  }
}