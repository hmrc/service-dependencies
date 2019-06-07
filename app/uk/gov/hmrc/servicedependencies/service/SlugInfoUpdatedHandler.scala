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
    .withCloseOnEmptyReceive(true)
    .withVisibilityTimeout(FiniteDuration(10, TimeUnit.SECONDS))

  val sqsSource =
    SqsSource(
      queueUrl,
      settings)(awsSqsClient)
      .map(messageToSlugInfo)
      .mapAsync(1)(saveSlugInfo)
      .runWith(SqsAckSink(queueUrl)(awsSqsClient))

  actorSystem.registerOnTermination(awsSqsClient.close())

  private def messageToSlugInfo(message: Message): (Message, SlugInfo) = {
    Logger.debug("starting messageToSlugInfo")
    val r = Json.parse(message.body()).as(ApiSlugInfoFormats.slugReads)
    Logger.debug(s"finished messageToSlugInfo for ${r.name}")
    (message, r)
  }

  private def saveSlugInfo(input: (Message, SlugInfo)): Future[MessageAction] = {
    val (message, slugInfo) = input
    Logger.debug(s"starting messageToSlugInfo for ${slugInfo.name}")
    slugInfoService.addSlugInfo(slugInfo).map {
      value => if(value) Delete(message) else Ignore(message)
    } recover {
      case _ => Ignore(message)
    }
  }
}
