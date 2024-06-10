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

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.model.{SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository
import uk.gov.hmrc.servicedependencies.service.DerivedViewsService

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentHandler @Inject()(
  configuration       : Configuration
, deploymentRepository: DeploymentRepository
, derivedViewsService : DerivedViewsService
)(using
  actorSystem    : ActorSystem,
  ec             : ExecutionContext
) extends SqsConsumer(
  name           = "Deployment"
, config         = SqsConfig("aws.sqs.deployment", configuration)
):

  private given HeaderCarrier = HeaderCarrier()

  private def prefix(payload: DeploymentHandler.DeploymentEvent) =
    s"Deployment (${payload.eventType}) ${payload.serviceName} ${payload.version.original} ${payload.environment.asString}"

  override protected def processMessage(message: Message): Future[MessageAction] =
    logger.info(s"Starting processing Deployment message with ID '${message.messageId()}'")
    (for
       payload <- EitherT
                    .fromEither[Future](Json.parse(message.body).validate(DeploymentHandler.mdtpEventReads).asEither)
                    .leftMap(error => s"Could not parse Deployment message with ID '${message.messageId()}'. Reason: $error")
       _       <- payload.eventType match {
                    case "deployment-complete"   => EitherT.right[String](deploymentRepository.setFlag  (payload.environment, payload.serviceName, payload.version))
                    case "undeployment-complete" => EitherT.right[String](deploymentRepository.clearFlag(payload.environment, payload.serviceName                 ))
                    case _                       => EitherT.right[String](Future.unit)
                  }
       _       <- EitherT.right[String](derivedViewsService.updateDerivedViews(repoName = payload.serviceName))
       _       =  logger.info(s"${prefix(payload)} with ID '${message.messageId()}' successfully processed.")
     yield
      MessageAction.Delete(message)
    ).value.map:
      case Left(error)   => logger.error(error); MessageAction.Ignore(message)
      case Right(action) => action

object DeploymentHandler:

  case class DeploymentEvent(
    eventType   : String
  , environment : SlugInfoFlag
  , serviceName : String
  , version     : Version
  , deploymentId: String
  , time        : Instant
  )

  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Reads, JsError, JsResult, JsSuccess, __}

  private def toResult[A](jsonValue: String, opt: Option[A]): JsResult[A] =
    opt match
      case Some(r) => JsSuccess(r)
      case None    => JsError(__, s"Not found - $jsonValue")

  lazy val readsSlugInfo: Reads[SlugInfoFlag] =
    _.validate[String]
     .flatMap(s => toResult(s, SlugInfoFlag.parse(s)))

  lazy val mdtpEventReads: Reads[DeploymentEvent] =
    ( (__ \ "event_type"          ).read[String]
    ~ (__ \ "environment"         ).read[SlugInfoFlag](readsSlugInfo)
    ~ (__ \ "microservice"        ).read[String]
    ~ (__ \ "microservice_version").read[Version](Version.format)
    ~ (__ \ "stack_id"            ).read[String]
    ~ (__ \ "event_date_time"     ).read[Instant]
    ){
      (eventType, environment, serviceName, version, deploymentId, time) =>
        val uniqueDeploymentId = if (deploymentId.startsWith("arn")) {
            s"${serviceName}-${environment.asString}-${version}-${time.toEpochMilli}"
          } else {
            deploymentId
          }
        DeploymentEvent(eventType, environment, serviceName, version, uniqueDeploymentId, time)
    }
