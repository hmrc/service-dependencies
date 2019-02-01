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
import uk.gov.hmrc.servicedependencies.model.{GroupArtefacts, MongoSlugInfoFormats, ServiceDependency, SlugInfo}

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


  private val readerServiceDependency = new BSONDocumentReader[ServiceDependency]{
    override def read(bson: BSONDocument): ServiceDependency = {
      val opt: Option[ServiceDependency] = for {
        slugName     <- bson.getAs[String]("slugName")
        slugVersion  <- bson.getAs[String]("slugVersion")
        depGroup     <- bson.getAs[String]("depGroup")
        depArtifact  <- bson.getAs[String]("depArtifact")
        depVersion   <- bson.getAs[String]("depVersion")
      } yield ServiceDependency(
          slugName    = slugName
        , slugVersion = slugVersion
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
        sortBySlug
      , List(
            groupByLatestSlug
          , unwindDependencies
          , matchArtifact
          , projectIntoServiceDependency
          )
      )
      .prepared
      .cursor
      .collect[List](-1, Cursor.FailOnError[List[ServiceDependency]]())
  }


  def findDistinctGroups: Future[Set[String]] =
    collection.distinct[String, Set]("dependencies.group")


  def findDistinctArtefacts(group: String): Future[Seq[String]] = {
    val col: BSONCollection = mongo.mongoConnector.db().collection(collectionName)

    import col.BatchCommands.AggregationFramework.{Ascending, FirstField, Group, GroupField, Match, Project, Sort, UnwindField}
    import reactivemongo.bson._

    implicit val rs = readerString

    col.aggregatorContext[String](
        Sort(Ascending("name"))
      , List(
          UnwindField("dependencies")
        , Match(document("dependencies.group" -> group))
        , Project(
            document(
                "group"    -> "$dependencies.group"
              , "artifact" -> "$dependencies.artifact"
            )
          )
        , GroupField("artifact")()
        )
      )
      .prepared
      .cursor
      .collect[List](-1, Cursor.FailOnError[List[String]]())
  }

  private val readerString = new BSONDocumentReader[String]{
    override def read(bson: BSONDocument): String =
      bson.getAs[String]("_id").get
  }


/*db.getCollection('slugInfos-bak').aggregate(
[
   {$unwind: {"path": "$dependencies"}}
   ,
   { $match: { 'dependencies.group': "org.slf4j" } }
   ,
   { $project:
     {group: "$dependencies.group", artifact: "$dependencies.artifact"}
   }
   ,
   { $group: {
         _id: "$artifact"
       }
   }
]
)*/



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

    import col.BatchCommands.AggregationFramework.{AddFieldToSet, Ascending, FirstField, Group, GroupField, Match, Project, Sort, UnwindField}
    import reactivemongo.bson._

    implicit val rga = readerGroupArtefacts

    col.aggregatorContext[GroupArtefacts](
        Sort(Ascending("group"))
      , List(
          UnwindField("dependencies")
        , Project(
            document(
                "group"    -> "$dependencies.group"
              , "artifact" -> "$dependencies.artifact"
            )
          )
        , Group(BSONString("$group"))("artifacts" -> AddFieldToSet("artifact"))
        )
      )
      .prepared
      .cursor
      .collect[List](-1, Cursor.FailOnError[List[GroupArtefacts]]())
  }



/*
db.getCollection('slugInfos').aggregate(
[
   {$unwind: {"path": "$dependencies"}}
   ,
   { $project:
     {org: "$dependencies.group", artifact: "$dependencies.artifact"}
   }
   ,
   { $group: {
         _id: "$org"
       , artifacts: { $addToSet: "$artifact"}
       }
   }
]
)
*/
}