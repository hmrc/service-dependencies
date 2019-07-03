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

package uk.gov.hmrc.servicedependencies.service

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.sqs.MessageAction.{Delete, Ignore}
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logger
import play.api.libs.json.Json
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.servicedependencies.config.ArtefactReceivingConfig
import uk.gov.hmrc.servicedependencies.model.{ApiSlugInfoFormats, SlugInfo}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

class SlugInfoUpdatedHandler @Inject()
(config: ArtefactReceivingConfig, slugInfoService: SlugInfoService)
(implicit val actorSystem: ActorSystem,
 implicit val materializer: Materializer,
 implicit val executionContext: ExecutionContext) {

  val logger = Logger("application.SlugInfoUpdatedHandler")

  if(!config.isEnabled) {
    logger.debug("SlugInfoUpdatedHandler is disabled.")
  }

  private lazy val queueUrl = config.sqsSlugQueue
  private lazy val settings = SqsSourceSettings()

  private lazy val awsCredentialsProvider: AwsCredentialsProvider =
    Try(DefaultCredentialsProvider.builder().build()).recover {
      case NonFatal(e) => logger.error(e.getMessage, e); throw e
    }.get

  private lazy val awsSqsClient = Try({
    val client = SqsAsyncClient.builder().credentialsProvider(awsCredentialsProvider)
      .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
      .build()

    actorSystem.registerOnTermination(client.close())
    client
  }).recover {
    case NonFatal(e) => logger.error(e.getMessage, e); throw e
  }.get

  if(config.isEnabled) {
    SqsSource(
      queueUrl, settings)(awsSqsClient)
      .map(logMessage)
      .map(messageToSlugInfo)
      .mapAsync(10)(saveSlugInfo)
      .map(acknowledge)
      .runWith(SqsAckSink(queueUrl)(awsSqsClient)).recover {
      case NonFatal(e) => logger.error(e.getMessage, e); throw e
    }
  }

  private def logMessage(message: Message): Message = {
    logger.debug(s"Starting processing message with ID '${message.messageId()}'")
    message
  }

  private def messageToSlugInfo(message: Message): (Message, Either[String, SlugInfo]) =
    (message,
      Json.parse(message.body())
        .validate(ApiSlugInfoFormats.slugReads)
        .asEither.left.map(error => s"Could not parse message with ID '${message.messageId()}'.  Reason: " + error.toString()))

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