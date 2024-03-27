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

package uk.gov.hmrc.servicedependencies.notification

import org.apache.pekko.actor.ActorSystem

import cats.data.EitherT
import cats.implicits._

import play.api.Configuration
import play.api.libs.json.Json

import software.amazon.awssdk.services.sqs.model.Message

import uk.gov.hmrc.servicedependencies.model.{MetaArtefactDependency, RepoType, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, MetaArtefactRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedDeployedDependencyRepository
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentHandler @Inject()(
  configuration                      : Configuration
, deploymentRepository               : DeploymentRepository
, derivedDeployedDependencyRepository: DerivedDeployedDependencyRepository
, metaArtefactRepository             : MetaArtefactRepository
)(implicit
  actorSystem    : ActorSystem,
  ec             : ExecutionContext
) extends SqsConsumer(
  name           = "Deployment"
, config         = SqsConfig("aws.sqs.deployment", configuration)
)(actorSystem, ec) {

  private def prefix(payload: DeploymentHandler.DeploymentEvent) =
    s"Deployment (${payload.eventType}) ${payload.serviceName} ${payload.version.original} ${payload.environment.asString}"

  override protected def processMessage(message: Message): Future[MessageAction] = {
    logger.info(s"Starting processing Deployment message with ID '${message.messageId()}'")
    (for {
      payload <- EitherT
                   .fromEither[Future](Json.parse(message.body).validate(DeploymentHandler.mdtpEventReads).asEither)
                   .leftMap(error => s"Could not parse Deployment message with ID '${message.messageId()}'. Reason: $error")
      _       <- payload.eventType match {
                   case "undeployment-complete" => updateDeploymentAndDerivedData(payload, isDeployment = false)
                   case "deployment-complete"   => updateDeploymentAndDerivedData(payload, isDeployment = true )
                   case _                       => EitherT.right[String](Future.unit)
                 }
      _       =  logger.info(s"${prefix(payload)} with ID '${message.messageId()}' successfully processed.")
    } yield
      MessageAction.Delete(message)
    ).value.map {
      case Left(error)   => logger.error(error); MessageAction.Ignore(message)
      case Right(action) => action
    }
  }

  private def updateDeploymentAndDerivedData(payload: DeploymentHandler.DeploymentEvent, isDeployment: Boolean): EitherT[Future, String, Unit] =
    for {
      oPrevious  <- EitherT.right[String](deploymentRepository.find(payload.environment, name = payload.serviceName))
      deployedIn =  oPrevious // Can only remove when version is not deployed elsewhere
                      .fold(List.empty[SlugInfoFlag])(_.flags)
                      .filterNot(List(SlugInfoFlag.Latest, payload.environment).contains)
      _          <- (oPrevious, deployedIn.isEmpty) match {
                      case (Some(prev), true)  => logger.info(s"${prefix(payload)} - deleting previous version ${prev.slugVersion.original} from DERIVED-deployed-dependencies")
                                                  EitherT.right[String](derivedDeployedDependencyRepository.delete(prev.slugName, Some(prev.slugVersion)))
                      case (Some(prev), false) => logger.info(s"${prefix(payload)} - not deleting previous version ${prev.slugVersion.original} from DERIVED-deployed-dependencies. Deployed in ${deployedIn.map(_.asString).mkString(",")} environments")
                                                  EitherT.right[String](Future.unit)
                      case _                   => EitherT.right[String](Future.unit)
                    }
      _          <- EitherT.right[String](
                      if  (isDeployment) deploymentRepository.setFlag  (payload.environment, payload.serviceName, payload.version)
                      else               deploymentRepository.clearFlag(payload.environment, payload.serviceName                 )
                    )
      oMeta      <- EitherT.right[String](metaArtefactRepository.find(repositoryName = payload.serviceName, version = payload.version))
      meta       <- EitherT.fromOption[Future](oMeta, s"${prefix(payload)} - Meta Artefact not found, cannot add to DERIVED-deployed-dependencies")
      deps       <- EitherT.right[String](derivedDeployedDependencyRepository.find(payload.environment, slugName = Some(payload.serviceName), slugVersion = Some(payload.version)))
      _          <- if (isDeployment && deps.isEmpty) {
                      logger.info(s"${prefix(payload)} - adding ${payload.version.original} to DERIVED-deployed-dependencies")
                      EitherT.right[String](derivedDeployedDependencyRepository.put(
                        DependencyGraphParser
                          .parseMetaArtefact(meta)
                          .map { case (node, scopes) => MetaArtefactDependency.apply(meta, RepoType.Service, node, scopes) }
                          .toSeq
                      ))
                    } else EitherT.right[String](Future.unit)
    } yield ()
}

object DeploymentHandler {

  case class DeploymentEvent(
    eventType   : String
  , environment : SlugInfoFlag
  , serviceName : String
  , version     : Version
  , deploymentId: String
  )

  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Reads, JsError, JsResult, JsSuccess, __}

  private def toResult[A](jsonValue: String, opt: Option[A]): JsResult[A] =
    opt match {
      case Some(r) => JsSuccess(r)
      case None    => JsError(__, s"Not found - $jsonValue")
    }

  lazy val readsSlugInfo: Reads[SlugInfoFlag] =
    _.validate[String]
     .flatMap(s => toResult(s, SlugInfoFlag.parse(s)))

  lazy val mdtpEventReads: Reads[DeploymentEvent] =
    ( (__ \ "event_type"          ).read[String]
    ~ (__ \ "environment"         ).read[SlugInfoFlag](readsSlugInfo)
    ~ (__ \ "microservice"        ).read[String]
    ~ (__ \ "microservice_version").read[Version](Version.format)
    ~ (__ \ "stack_id"            ).read[String]
    )(DeploymentEvent.apply _)
}
