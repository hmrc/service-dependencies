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

import java.util.concurrent.TimeUnit

import scala.concurrent.{ExecutionContext, Future}
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.{Inject, Singleton}
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import uk.gov.hmrc.servicedependencies.config.ArtefactReceivingConfig
import java.time.Duration

import akka.stream.alpakka.sqs.MessageAction.{Delete, Ignore}
import play.api.Logger
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.servicedependencies.model.{ApiSlugInfoFormats, SlugInfo}

import scala.concurrent.duration.FiniteDuration

@Singleton
class SlugInfoUpdatedHandler @Inject()
(config: ArtefactReceivingConfig, slugInfoService: SlugInfoService)
(implicit val actorSystem: ActorSystem,
 implicit val materializer: Materializer,
 implicit val executionContext: ExecutionContext) {

  lazy val awsSqsClient = SqsAsyncClient.builder()
    .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
    .build()

  val queueUrl = config.sqsSlugQueue

  val settings = SqsSourceSettings()
    .withWaitTime(Duration.ofMillis(10))
    .withParallelRequests(1)
    .withMaxBatchSize(1)
    .withCloseOnEmptyReceive(true)
    .withVisibilityTimeout(FiniteDuration(10, TimeUnit.SECONDS))

  val sqsSource =
    SqsSource(
      queueUrl,
      settings)(awsSqsClient)
      .map(messageToSlugInfo)
      .mapAsync(1)(saveSlugInfo)
      .map(acknowledge)
      .runWith(SqsAckSink(queueUrl)(awsSqsClient))

  actorSystem.registerOnTermination(awsSqsClient.close())

  private def acknowledge(input: (Message, Either[String, Unit])) = {
    val (message, eitherResult) = input
    eitherResult match {
      case Left(error) =>
        Logger.error(error)
        Ignore(message)
      case Right(_) =>
        Logger.info("Message successfully processed.")
        Delete(message)
    }
  }

  private def messageToSlugInfo(message: Message): (Message, Either[String, SlugInfo]) =
    (message,
      Json.parse(message.body())
        .validate(ApiSlugInfoFormats.slugReads)
        .asEither.left.map(error => "could not parse: " + error.toString()))

  private def saveSlugInfo(input: (Message, Either[String, SlugInfo])): Future[(Message, Either[String, Unit])] = {
    val (message, eitherSlugInfo) = input

    (eitherSlugInfo match {
      case Left(error) => Future(Left(error))
      case Right(slugInfo) =>
        slugInfoService.addSlugInfo(slugInfo)
          .map(saveResult => if (saveResult) Right(()) else Left("SlugInfo was sent on but not saved."))
          .recover {
          case e =>
            Logger.error("Could not store slug info", e)
            Left("Could not store slug info: " + e.getMessage)
        }
    }).map((message, _))
  }
}