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
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONDocumentReader, BSONObjectID}
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model.{MongoSlugInfoFormats, ServiceDependency, SlugInfo}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class SlugInfoRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[SlugInfo, BSONObjectID](
    collectionName = "slugInfos",
    mongo          = mongo.mongoConnector.db,
    domainFormat   = MongoSlugInfoFormats.siFormat){

  import MongoSlugInfoFormats._

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
      .insert(slugInfo)
      .map(_ => true)
      .recover { case MongoErrors.Duplicate(_) => false }

  def getAllEntries: Future[Seq[SlugInfo]] =
    findAll()

  def clearAllData: Future[Boolean] =
    super.removeAll().map(_.ok)

  def getSlugInfos(name: String, optVersion: Option[String]): Future[Seq[SlugInfo]] =
    optVersion match {
      case None          => find("name" -> name)
      case Some(version) => find("name" -> name, "version" -> version)
    }


  implicit object ReaderServiceDependency extends BSONDocumentReader[ServiceDependency]{
    override def read(bson: BSONDocument): ServiceDependency = {
      val opt: Option[ServiceDependency] = for {
        slugName     <- bson.getAs[String]("slugName")
        slugVersion  <- bson.getAs[String]("slugVersion")
        depGroup     <- bson.getAs[String]("depGroup")
        depArtifact  <- bson.getAs[String]("depArtifact")
        depVersion   <- bson.getAs[String]("depVersion")
      } yield ServiceDependency(
        slugName = slugName
        , slugVersion = slugVersion
        , depGroup = depGroup
        , depArtefact = depArtifact
        , depVersion = depVersion
      )
      opt.get
    }
  }


  def findServices(group: String, artefact: String): Future[Seq[ServiceDependency]] =
    findServices(group, artefact, mongo.mongoConnector.db().collection(collectionName))


  private def findServices(group: String, artefact: String, col: BSONCollection): Future[Seq[ServiceDependency]] = {

    import col.BatchCommands.AggregationFramework.{Ascending, Descending, FirstField, Group, Match, Project, Sort, UnwindField}
    import reactivemongo.bson._

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
         "_id" -> 0
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
      sortBySlug,
      List(
         groupByLatestSlug
        ,unwindDependencies
        ,matchArtifact
        ,projectIntoServiceDependency
      ))
      .prepared
      .cursor
      .collect[List](-1, Cursor.FailOnError[List[ServiceDependency]]())
  }

}
