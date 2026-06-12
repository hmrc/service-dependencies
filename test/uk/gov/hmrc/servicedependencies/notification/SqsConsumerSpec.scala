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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, DeleteMessageResponse, Message, ReceiveMessageRequest, ReceiveMessageResponse}

import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class SqsConsumerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with Eventually
     with BeforeAndAfterEach:

  private given ExecutionContext = ExecutionContext.global

  private val sqsClient = mock[SqsAsyncClient]

  override def beforeEach(): Unit =
    super.beforeEach()
    reset(sqsClient)
    when(sqsClient.deleteMessage(any[DeleteMessageRequest]))
      .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()))

  "SqsConsumer" should {
    "wait for a message to finish processing before polling and processing the next message" in {
      given actorSystem: ActorSystem = ActorSystem("sqs-consumer-spec")

      val firstMessage  = sqsMessage("first")
      val secondMessage = sqsMessage("second")
      val receives      = AtomicInteger(0)
      val started       = ConcurrentLinkedQueue[String]()
      val completions   = Map(
        "first"  -> Promise[Unit](),
        "second" -> Promise[Unit]()
      )

      when(sqsClient.receiveMessage(any[ReceiveMessageRequest]))
        .thenAnswer: _ =>
          val response =
            receives.getAndIncrement() match
              case 0 => ReceiveMessageResponse.builder().messages(firstMessage).build()
              case 1 => ReceiveMessageResponse.builder().messages(secondMessage).build()
              case _ => ReceiveMessageResponse.builder().messages(java.util.List.of()).build()

          CompletableFuture.completedFuture(response)

      try
        TestSqsConsumer(
          config = testConfig,
          client = sqsClient,
          onProcess = message =>
            started.add(message.messageId())
            completions(message.messageId()).future.map(_ => MessageAction.Ignore(message))
        )

        eventually {
          started.asScala.toList should contain ("first")
        }

        Thread.sleep(testPollInterval.toMillis * 3)

        started.asScala.toList shouldBe List("first")
      finally
        completions.values.foreach(_.trySuccess(()))
        actorSystem.terminate().futureValue
    }
  }

  private def sqsMessage(id: String): Message =
    Message
      .builder()
      .messageId(id)
      .receiptHandle(s"$id-receipt-handle")
      .body("{}")
      .build()

  private val testPollInterval = 50.millis

  private def testConfig: SqsConfig =
    SqsConfig(
      keyPrefix     = "aws.sqs.meta",
      configuration = Configuration.from(
        Map(
          "aws.sqs.watchdogTimeout"       -> "10 seconds",
          "aws.sqs.meta.queueUrl"         -> "http://localhost/test-queue",
          "aws.sqs.meta.maxNumberOfMessages" -> 1,
          "aws.sqs.meta.waitTimeSeconds"     -> 0,
          "aws.sqs.meta.pollInterval"        -> s"${testPollInterval.toMillis} milliseconds"
        )
      )
    )

  private class TestSqsConsumer(
    config   : SqsConfig,
    client   : SqsAsyncClient,
    onProcess: Message => Future[MessageAction]
  )(using
    actorSystem: ActorSystem,
    ec         : ExecutionContext
  ) extends SqsConsumer(
    name   = "MetaArtefact",
    config = config
  ):
    override protected def buildSqsClient(): SqsAsyncClient =
      client

    override protected def processMessage(message: Message): Future[MessageAction] =
      onProcess(message)
