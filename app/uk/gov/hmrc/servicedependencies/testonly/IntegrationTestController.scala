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

package uk.gov.hmrc.servicedependencies.testonly

import cats.implicits._
import javax.inject.Inject
import org.mongodb.scala.bson.BsonDocument
import play.api.libs.json._
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._
import uk.gov.hmrc.servicedependencies.service.{DerivedViewsService, SlugInfoService}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedServiceDependenciesRepository

import scala.concurrent.{ExecutionContext, Future}

class IntegrationTestController @Inject()(
    latestVersionRepository         : LatestVersionRepository
  , slugInfoRepo                    : SlugInfoRepository
  , slugInfoService                 : SlugInfoService
  , bobbyRulesSummaryRepo           : BobbyRulesSummaryRepository
  , derivedViewsService             : DerivedViewsService
  , deploymentsRepo                 : DeploymentRepository
  , derivedServiceDependenciesRepository: DerivedServiceDependenciesRepository
  , metaArtefactRepository          : MetaArtefactRepository
  , cc                              : ControllerComponents
  )(implicit ec: ExecutionContext
  ) extends BackendController(cc) {

  private def validateJson[A : Reads] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def addLatestVersions = {
    implicit val latestVersionReads = {
      implicit val vf = Version.format
      Json.using[Json.WithDefaultValues].reads[LatestVersion]
    }

    Action.async(validateJson[List[LatestVersion]]) { implicit request =>
      request.body.traverse(latestVersionRepository.update)
        .map(_ => NoContent)
    }
  }

  def deleteLatestVersions =
    Action.async {
      latestVersionRepository.clearAllData()
        .map(_ => NoContent)
    }

  def addMetaArtefacts = {
    implicit val maf = MetaArtefact.apiFormat
    Action.async(validateJson[List[MetaArtefact]]) { implicit request =>
      request.body.traverse(metaArtefactRepository.add)
        .map(_ => NoContent)
    }
  }

 def deleteMetaArtefacts =
    Action.async {
      metaArtefactRepository.clearAllData()
        .map(_ => NoContent)
    }

  def addSluginfos = {
    implicit val siwfr = SlugInfoWithFlags.reads
    Action.async(validateJson[List[SlugInfoWithFlags]]) { implicit request =>
      request.body.traverse { slugInfoWithFlag =>
        def updateFlag(slugInfoWithFlag: SlugInfoWithFlags, flag: SlugInfoFlag, toSet: SlugInfoWithFlags => Boolean): Future[Unit] =
          if (toSet(slugInfoWithFlag))
            deploymentsRepo.setFlag(flag, slugInfoWithFlag.slugInfo.name, slugInfoWithFlag.slugInfo.version)
          else
            Future.unit

        for {
          _ <- slugInfoService.addSlugInfo(
                 slugInfoWithFlag.slugInfo,
                 metaArtefact = None // addMetaArtefacts should be called before addSluginfos
               )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Latest      , _.latest      )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Production  , _.production  )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.QA          , _.qa          )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Staging     , _.staging     )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Development , _.development )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.ExternalTest, _.externalTest)
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Integration , _.integration )
          _ <- derivedViewsService.generateAllViews()
        } yield ()
      }.map(_ => NoContent)
    }
  }

  def deleteSluginfos =
    Action.async {
      for {
        _ <- slugInfoRepo.clearAllData()
        _ <- derivedServiceDependenciesRepository.collection.deleteMany(BsonDocument()).toFuture()
      } yield NoContent
    }

  def addBobbyRulesSummaries = {
    implicit val brsf = BobbyRulesSummary.apiFormat
    Action.async(validateJson[List[BobbyRulesSummary]]) { implicit request =>
      request.body.traverse(bobbyRulesSummaryRepo.add)
        .map(_ => NoContent)
    }
  }

  def deleteBobbyRulesSummaries =
    Action.async {
      bobbyRulesSummaryRepo.clearAllData()
        .map(_ => NoContent)
    }

  def deleteAll =
    Action.async {
      List(
          latestVersionRepository.clearAllData()
        , slugInfoRepo.clearAllData()
        , bobbyRulesSummaryRepo.clearAllData()
        , deploymentsRepo.clearAllData()
        , derivedServiceDependenciesRepository.collection.deleteMany(BsonDocument()).toFuture()
        , metaArtefactRepository.clearAllData()
        ).sequence
         .map(_ => NoContent)
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
