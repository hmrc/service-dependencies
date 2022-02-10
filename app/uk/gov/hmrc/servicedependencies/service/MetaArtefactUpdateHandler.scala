/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.service

import akka.actor.ActorSystem
import akka.stream.{ActorAttributes, Materializer, Supervision}
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import cats.data.EitherT
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.ArtefactReceivingConfig
import uk.gov.hmrc.servicedependencies.connector.ArtefactProcessorConnector
import uk.gov.hmrc.servicedependencies.model.Version

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

@Singleton
class MetaArtefactUpdateHandler @Inject()(
  config                    : ArtefactReceivingConfig,
  artefactProcessorConnector: ArtefactProcessorConnector,
  metaArtefactService       : MetaArtefactService
)(implicit
  actorSystem : ActorSystem,
  materializer: Materializer,
  ec          : ExecutionContext
) extends Logging {

  private lazy val metaQueueUrl = config.sqsMetaArtefactQueue
  private lazy val settings = SqsSourceSettings()

  private implicit val hc = HeaderCarrier()

  private lazy val awsSqsClient =
    Try {
      val client = SqsAsyncClient.builder()
        .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
        .build()

      actorSystem.registerOnTermination(client.close())
      client
    }.recoverWith {
      case NonFatal(e) => logger.error(s"Failed to set up awsSqsClient: ${e.getMessage}", e); Failure(e)
    }.get


  if (config.isEnabled) {
    // Meta Artefact Processor
    SqsSource(metaQueueUrl, settings)(awsSqsClient)
      .mapAsync(10)(processMetaArtefactMessage)
      .withAttributes(ActorAttributes.supervisionStrategy {
        case NonFatal(e) => logger.error(s"Failed to process meta artefact sqs messages: ${e.getMessage}", e); Supervision.Restart
      })
      .runWith(SqsAckSink(metaQueueUrl)(awsSqsClient))
  } else {
    logger.info("MetaArtefactUpdateHandler is disabled")
  }


  private def processMetaArtefactMessage(message: Message): Future[MessageAction] = {
    logger.debug(s"Starting processing MetaArtefact message with ID '${message.messageId()}'")
    (for {
       available    <- EitherT.fromEither[Future](
                         Json.parse(message.body)
                           .validate(JobAvailable.reads)
                           .asEither.left.map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                       )
       _            <- EitherT.cond[Future](available.jobType == "meta", (), s"${available.jobType} was not 'meta'")
       metaArtefact <- EitherT.fromOptionF(
                         artefactProcessorConnector.getMetaArtefact(available.name, available.version),
                         s"MetaArtefact for name: ${available.name}, version: ${available.version} was not found"
                       )
       _            <- EitherT(
                         metaArtefactService.addMetaArtefact(metaArtefact)
                           .map(Right.apply)
                           .recover {
                             case e =>
                               val errorMessage = s"Could not store MetaArtefact for message with ID '${message.messageId()}' (${metaArtefact.name} ${metaArtefact.version})"
                               logger.error(errorMessage, e)
                               Left(s"$errorMessage ${e.getMessage}")
                           }
                       )
     } yield metaArtefact
    ).value.map {
      case Left(error) =>
        logger.error(error)
        MessageAction.Ignore(message)
      case Right(metaArtefact) =>
        logger.info(s"MetaArtefact message with ID '${message.messageId()}' (${metaArtefact.name} ${metaArtefact.version}) successfully processed.")
        MessageAction.Delete(message)
    }
  }
}

case class JobAvailable(
  jobType: String,
  name   : String,
  version: Version,
  url    : String
)

object JobAvailable {
  import play.api.libs.json.{Reads, __}
  val reads: Reads[JobAvailable] = {
    import play.api.libs.functional.syntax._
    implicit val vr  = Version.format
    ( (__ \ "jobType").read[String]
    ~ (__ \ "name"   ).read[String]
    ~ (__ \ "version").read[Version]
    ~ (__ \ "url"    ).read[String]
    )(apply _)
  }
}
