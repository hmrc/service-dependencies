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

package uk.gov.hmrc.servicedependencies.service


import akka.actor.ActorSystem
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.{ActorAttributes, Materializer, Supervision}
import cats.data.EitherT
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.servicedependencies.config.ArtefactReceivingConfig
import uk.gov.hmrc.servicedependencies.model.MetaArtefact

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

@Singleton
class MetaArtefactUpdateHandler @Inject()(
                                          config             : ArtefactReceivingConfig,
                                          metaArtefactService: MetaArtefactService,
                                          messageHandling    : SqsMessageHandling
                                        )(implicit
                                          actorSystem : ActorSystem,
                                          materializer: Materializer,
                                          ec          : ExecutionContext
                                        ) extends Logging {


  private lazy val metaQueueUrl = config.sqsMetaArtefactQueue
  private lazy val settings = SqsSourceSettings()

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
    logger.debug(s"Starting processing meta-artefact message with ID '${message.messageId()}'")

    (for {
      metaArtefact <- EitherT(messageHandling
        .decompress(message.body)
        .map(decompressed =>
          Json.parse(decompressed)
            .validate(MetaArtefact.apiFormat)
            .asEither.left.map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)))
      _ <- EitherT(
        metaArtefactService.addMetaArtefact(metaArtefact).map(Right.apply).recover {
          case e =>
            val errorMessage = s"Could not store meta-artefact for message with ID '${message.messageId()}'"
            logger.error(errorMessage, e)
            Left(s"$errorMessage ${e.getMessage}")
        })
    } yield ()).value.map {
      case Left(error) =>
        logger.error(error)
        MessageAction.Ignore(message)
      case Right(_) =>
        logger.info(s"Meta artefact message with ID '${message.messageId()}' successfully processed.")
        MessageAction.Delete(message)
    }
  }

}
