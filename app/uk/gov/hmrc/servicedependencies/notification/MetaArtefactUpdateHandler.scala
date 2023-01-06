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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorAttributes, Materializer, Supervision}
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import akka.stream.scaladsl.Source
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
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

@Singleton
class MetaArtefactUpdateHandler @Inject()(
  config                    : ArtefactReceivingConfig,
  artefactProcessorConnector: ArtefactProcessorConnector,
  metaArtefactRepository    : MetaArtefactRepository
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

  def dedupe(source: Source[Message, NotUsed]): Source[Message, NotUsed] =
      Source.single(Message.builder.messageId("----------").build) // dummy value since the dedupe will ignore the first entry
      .concat(source)
      // are we getting duplicates?
      .sliding(2, 1)
      .mapConcat { case prev +: current +: _=>
          if (prev.messageId == current.messageId) {
            logger.warn(s"Read the same meta-artefact message ID twice ${prev.messageId} - ignoring duplicate")
            List.empty
          }
          else List(current)
      }

  if (config.isEnabled)
    dedupe(SqsSource(metaQueueUrl, settings)(awsSqsClient))
      .mapAsync(10)(processMetaArtefactMessage)
      .withAttributes(ActorAttributes.supervisionStrategy {
        case NonFatal(e) => logger.error(s"Failed to process meta artefact sqs messages: ${e.getMessage}", e); Supervision.Restart
      })
      .runWith(SqsAckSink(metaQueueUrl)(awsSqsClient))
  else
    logger.info("MetaArtefactUpdateHandler is disabled")


  private def processMetaArtefactMessage(message: Message): Future[MessageAction] = {
    logger.debug(s"Starting processing MetaArtefact message with ID '${message.messageId()}'")
    (for {
       payload <- EitherT.fromEither[Future](
                    Json.parse(message.body)
                      .validate(MessagePayload.reads)
                      .asEither.left.map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                  )
      action   <- payload match {
                    case available: MessagePayload.JobAvailable =>
                      for {
                        _            <- EitherT.cond[Future](available.jobType == "meta", (), s"${available.jobType} was not 'meta'")
                        metaArtefact <- EitherT.fromOptionF(
                                          artefactProcessorConnector.getMetaArtefact(available.name, available.version),
                                          s"MetaArtefact for name: ${available.name}, version: ${available.version} was not found"
                                        )
                        _            <- EitherT(
                                          metaArtefactRepository.add(metaArtefact)
                                            .map(Right.apply)
                                            .recover {
                                              case e =>
                                                val errorMessage = s"Could not store MetaArtefact for message with ID '${message.messageId()}' (${metaArtefact.name} ${metaArtefact.version})"
                                                logger.error(errorMessage, e)
                                                Left(s"$errorMessage ${e.getMessage}")
                                            }
                                        )
                      } yield {
                        logger.info(s"MetaArtefact available message with ID '${message.messageId()}' (${metaArtefact.name} ${metaArtefact.version}) successfully processed.")
                        MessageAction.Delete(message)
                      }
                    case deleted: MessagePayload.JobDeleted =>
                      for {
                        _ <- EitherT.cond[Future](deleted.jobType == "meta", (), s"${deleted.jobType} was not 'meta'")
                        _ <- EitherT(
                               metaArtefactRepository.delete(deleted.name, deleted.version)
                                 .map(Right.apply)
                                 .recover {
                                   case e =>
                                     val errorMessage = s"Could not delete MetaArtefact for message with ID '${message.messageId()}' (${deleted.name} ${deleted.version})"
                                     logger.error(errorMessage, e)
                                     Left(s"$errorMessage ${e.getMessage}")
                                 }
                             )
                       } yield {
                         logger.info(s"MetaArtefact deleted message with ID '${message.messageId()}' (${deleted.name} ${deleted.version}) successfully processed.")
                         MessageAction.Delete(message)
                       }
                  }
     } yield action
    ).value.map {
      case Left(error) =>
        logger.error(error)
        MessageAction.Ignore(message)
      case Right(action) =>
        action
    }
  }
}
