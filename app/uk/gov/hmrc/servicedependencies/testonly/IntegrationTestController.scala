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

package uk.gov.hmrc.servicedependencies.testonly

import cats.implicits._
import javax.inject.Inject
import org.mongodb.scala.bson.BsonDocument
import play.api.libs.json._
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._
import uk.gov.hmrc.servicedependencies.service.{DerivedViewsService, SlugInfoService}

import scala.concurrent.{ExecutionContext, Future}

class IntegrationTestController @Inject()(
    latestVersionRepository         : LatestVersionRepository
  , repositoryDependenciesRepository: RepositoryDependenciesRepository
  , slugInfoRepo                    : SlugInfoRepository
  , slugInfoService                 : SlugInfoService
  , bobbyRulesSummaryRepo           : BobbyRulesSummaryRepository
  , derivedViewsService             : DerivedViewsService
  , deploymentsRepo                 : DeploymentRepository
  , mongoComponent                  : MongoComponent
  , cc                              : ControllerComponents
  )(implicit ec: ExecutionContext
  ) extends BackendController(cc) {

  implicit val dtf                = MongoJavatimeFormats.localDateFormat
  implicit val vf                 = Version.apiFormat
  implicit val latestVersionReads = Json.using[Json.WithDefaultValues].reads[MongoLatestVersion]
  implicit val dependenciesReads  = Json.using[Json.WithDefaultValues].reads[MongoRepositoryDependencies]
  implicit val siwfr              = SlugInfoWithFlags.reads
  implicit val brsf               = BobbyRulesSummary.apiFormat

  private def validateJson[A : Reads] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def addLatestVersions =
    Action.async(validateJson[List[MongoLatestVersion]]) { implicit request =>
      request.body.traverse(latestVersionRepository.update)
        .map(_ => NoContent)
    }

  def addDependencies =
    Action.async(validateJson[List[MongoRepositoryDependencies]]) { implicit request =>
      request.body.traverse(repositoryDependenciesRepository.update)
        .map(_ => NoContent)
    }

  def addSluginfos =
    Action.async(validateJson[List[SlugInfoWithFlags]]) { implicit request =>
      request.body.traverse { slugInfoWithFlag =>
        def updateFlag(slugInfoWithFlag: SlugInfoWithFlags, flag: SlugInfoFlag, toSet: SlugInfoWithFlags => Boolean): Future[Unit] =
          if (toSet(slugInfoWithFlag))
            deploymentsRepo.setFlag(flag, slugInfoWithFlag.slugInfo.name, slugInfoWithFlag.slugInfo.version)
          else
            Future(())
        for {
          _ <- slugInfoService.addSlugInfo(slugInfoWithFlag.slugInfo)
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Latest      , _.latest      )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Production  , _.production  )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.QA          , _.qa          )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Staging     , _.staging     )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Development , _.development )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.ExternalTest, _.externalTest)
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Integration , _.integration )
          //_ <- derivedViewsService.generateAllViews()
        } yield ()
      }.map(_ => NoContent)
    }

  def addBobbyRulesSummaries =
    Action.async(validateJson[List[BobbyRulesSummary]]) { implicit request =>
      request.body.traverse(bobbyRulesSummaryRepo.add)
        .map(_ => NoContent)
    }

  def deleteLatestVersions =
    Action.async {
      latestVersionRepository.clearAllData
        .map(_ => NoContent)
    }

  def deleteDependencies =
    Action.async {
      repositoryDependenciesRepository.clearAllData
        .map(_ => NoContent)
    }

  def deleteSluginfos =
    Action.async {
      slugInfoRepo.clearAllData
        .map(_ => NoContent)
    }

  def deleteBobbyRulesSummaries =
    Action.async {
      bobbyRulesSummaryRepo.clearAllData
        .map(_ => NoContent)
    }

  def deleteAll =
    Action.async {
      List(
          latestVersionRepository.clearAllData
        , repositoryDependenciesRepository.clearAllData
        , slugInfoRepo.clearAllData
        , bobbyRulesSummaryRepo.clearAllData
        , deploymentsRepo.clearAllData
        , mongoComponent.database.getCollection("DERIVED-slug-dependencies").deleteMany(BsonDocument()).toFuture
        ).sequence
         .map(_ => NoContent)
    }

  def createDerivedViews =
    Action.async {
      derivedViewsService.generateAllViews().map(_ => NoContent)
    }

  case class SlugInfoWithFlags(
    slugInfo    : SlugInfo,
    latest      : Boolean,
    production  : Boolean,
    qa          : Boolean,
    staging     : Boolean,
    development : Boolean,
    externalTest: Boolean,
    integration : Boolean
  )

  object SlugInfoWithFlags {
    import play.api.libs.functional.syntax._
    import play.api.libs.json.__

    val reads: Reads[SlugInfoWithFlags] = {
        implicit val sif = ApiSlugInfoFormats.slugInfoFormat
        ( (__                 ).read[SlugInfo]
        ~ (__ \ "latest"      ).read[Boolean]
        ~ (__ \ "production"  ).read[Boolean]
        ~ (__ \ "qa"          ).read[Boolean]
        ~ (__ \ "staging"     ).read[Boolean]
        ~ (__ \ "development" ).read[Boolean]
        ~ (__ \ "externalTest").read[Boolean]
        ~ (__ \ "integration" ).read[Boolean]
        )(SlugInfoWithFlags.apply _)
    }
  }
}
