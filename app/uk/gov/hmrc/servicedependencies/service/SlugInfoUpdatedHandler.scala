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
import akka.stream.{ActorAttributes, Materializer, Supervision}
import akka.stream.alpakka.sqs.MessageAction.{Delete, Ignore}
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.servicedependencies.config.ArtefactReceivingConfig
import uk.gov.hmrc.servicedependencies.model.{ApiSlugInfoFormats, SlugInfo}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

class SlugInfoUpdatedHandler @Inject()(
  config         : ArtefactReceivingConfig,
  slugInfoService: SlugInfoService,
  messageHandling: SqsMessageHandling
)(implicit
   actorSystem : ActorSystem,
   materializer: Materializer,
   ec          : ExecutionContext
) extends Logging {

  if (!config.isEnabled) {
    logger.debug("SlugInfoUpdatedHandler is disabled.")
  }

  private lazy val queueUrl = config.sqsSlugQueue
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
    SqsSource(
      queueUrl, settings)(awsSqsClient)
      .mapAsync(10)(processMessage)
      .map(acknowledge)
      .withAttributes(ActorAttributes.supervisionStrategy {
        case NonFatal(e) => logger.error(s"Failed to process sqs messages: ${e.getMessage}", e); Supervision.Restart
      })
      .runWith(SqsAckSink(queueUrl)(awsSqsClient))
  }

  private def processMessage(message: Message): Future[(Message, Either[String, Unit])] = {
    logger.debug(s"Starting processing message with ID '${message.messageId()}'")
    for {
      parsed <- messageHandling
                  .decompress(message.body)
                  .map(decompressed =>
                    Json.parse(decompressed)
                    .validate(ApiSlugInfoFormats.slugReads)
                    .asEither.left.map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                  )
      saved  <- saveSlugInfo((message, parsed))
    } yield saved
  }

  private def saveSlugInfo(input: (Message, Either[String, SlugInfo])): Future[(Message, Either[String, Unit])] = {
    val (message, eitherSlugInfo) = input

    (eitherSlugInfo match {
      case Left(error) => Future(Left(error))
      case Right(slugInfo) =>
        slugInfoService.addSlugInfo(slugInfo)
          .map(saveResult => if (saveResult) Right(()) else Left(s"SlugInfo for message (ID '${message.messageId()}') was sent on but not saved."))
          .recover {
            case e =>
              val errorMessage = s"Could not store slug info for message with ID '${message.messageId()}'"
              logger.error(errorMessage, e)
              Left(s"$errorMessage ${e.getMessage}")
          }
    }).map((message, _))
  }

  private def acknowledge(input: (Message, Either[String, Unit])) = {
    val (message, eitherResult) = input
    eitherResult match {
      case Left(error) =>
        logger.error(error)
        Ignore(message)
      case Right(_) =>
        logger.debug(s"Message with ID '${message.messageId()}' successfully processed.")
        Delete(message)
    }
  }
}
