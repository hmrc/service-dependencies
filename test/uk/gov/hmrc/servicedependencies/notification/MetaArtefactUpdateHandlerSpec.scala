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

import com.mongodb.{MongoCommandException, ServerAddress}
import org.apache.pekko.actor.ActorSystem
import org.bson.BsonDocument
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{reset, times, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, DeleteMessageResponse, Message, ReceiveMessageRequest, ReceiveMessageResponse}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.ArtefactProcessorConnector
import uk.gov.hmrc.servicedependencies.model.Version
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository
import uk.gov.hmrc.servicedependencies.service.DerivedViewsService

import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class MetaArtefactUpdateHandlerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach:

  private given ExecutionContext = ExecutionContext.global

  private val sqsClient                  = mock[SqsAsyncClient]
  private val artefactProcessorConnector = mock[ArtefactProcessorConnector]
  private val metaArtefactRepository     = mock[MetaArtefactRepository]
  private val derivedViewsService        = mock[DerivedViewsService]

  override def beforeEach(): Unit =
    super.beforeEach()
    reset(sqsClient, artefactProcessorConnector, metaArtefactRepository, derivedViewsService)
    when(sqsClient.receiveMessage(any[ReceiveMessageRequest]))
      .thenReturn(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().build()))
    when(sqsClient.deleteMessage(any[DeleteMessageRequest]))
      .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()))

  "MetaArtefactUpdateHandler" should {
    "retry transient Mongo write conflicts with exponential backoff when deleting meta artefacts" in {
      given actorSystem: ActorSystem = ActorSystem("meta-artefact-update-handler-spec")

      val version     = Version("10.245.24")
      val message     = deletionMessage("message-id", "email", version)
      val attempts    = AtomicInteger(0)
      val attemptTime = ConcurrentLinkedQueue[Long]()

      when(metaArtefactRepository.delete(eqTo("email"), eqTo(version)))
        .thenAnswer: _ =>
          attemptTime.add(System.nanoTime())
          if attempts.getAndIncrement() < 2 then
            Future.failed(writeConflict)
          else
            Future.unit

      when(derivedViewsService.updateDerivedViews(eqTo("email"))(using any[HeaderCarrier]))
        .thenReturn(Future.unit)

      TestMetaArtefactUpdateHandler.client = sqsClient

      try
        val underTest =
          TestMetaArtefactUpdateHandler(
            configuration              = testConfig,
            artefactProcessorConnector = artefactProcessorConnector,
            metaArtefactRepository     = metaArtefactRepository,
            derivedViewsService        = derivedViewsService
          )

        underTest.handle(message).futureValue shouldBe MessageAction.Delete(message)

        verify(metaArtefactRepository, times(3)).delete(eqTo("email"), eqTo(version))
        verify(derivedViewsService).updateDerivedViews(eqTo("email"))(using any[HeaderCarrier])

        val gaps = attemptTime.asScala.toList
          .sliding(2)
          .collect { case Seq(a, b) => (b - a).nanos.toMillis }
          .toList

        gaps.size shouldBe 2
        gaps.sum should be >= 75L
      finally
        actorSystem.terminate().futureValue
    }
  }

  private def deletionMessage(messageId: String, name: String, version: Version): Message =
    Message
      .builder()
      .messageId(messageId)
      .receiptHandle(s"$messageId-receipt-handle")
      .body(
        Json.obj(
          "type"    -> "deletion",
          "jobType" -> "meta",
          "name"    -> name,
          "version" -> version.original,
          "url"     -> "http://artefact-processor/meta"
        ).toString()
      )
      .build()

  private def writeConflict: MongoCommandException =
    new MongoCommandException(
      BsonDocument.parse(
        """{
          |  "ok": 0.0,
          |  "code": 112,
          |  "codeName": "WriteConflict",
          |  "errmsg": "Write conflict during plan execution and yielding is disabled. :: Please retry your operation or multi-document transaction.",
          |  "errorLabels": ["TransientTransactionError"]
          |}""".stripMargin
      ),
      new ServerAddress("mongo-1", 27017)
    )

  private def testConfig: Configuration =
    Configuration.from(
      Map(
        "aws.sqs.watchdogTimeout"          -> "10 seconds",
        "aws.sqs.meta.queueUrl"            -> "http://localhost/test-queue",
        "aws.sqs.meta.maxNumberOfMessages" -> 1,
        "aws.sqs.meta.waitTimeSeconds"     -> 0,
        "aws.sqs.meta.pollInterval"        -> "1 hour"
      )
    )

  private object TestMetaArtefactUpdateHandler:
    var client: SqsAsyncClient = _

    def apply(
      configuration             : Configuration,
      artefactProcessorConnector: ArtefactProcessorConnector,
      metaArtefactRepository    : MetaArtefactRepository,
      derivedViewsService       : DerivedViewsService
    )(using
      actorSystem: ActorSystem,
      ec         : ExecutionContext
    ): TestMetaArtefactUpdateHandler =
      new TestMetaArtefactUpdateHandler(
        configuration              = configuration,
        artefactProcessorConnector = artefactProcessorConnector,
        metaArtefactRepository     = metaArtefactRepository,
        derivedViewsService        = derivedViewsService
      )

  private class TestMetaArtefactUpdateHandler(
    configuration             : Configuration,
    artefactProcessorConnector: ArtefactProcessorConnector,
    metaArtefactRepository    : MetaArtefactRepository,
    derivedViewsService       : DerivedViewsService
  )(using
    actorSystem: ActorSystem,
    ec         : ExecutionContext
  ) extends MetaArtefactUpdateHandler(
    configuration              = configuration,
    artefactProcessorConnector = artefactProcessorConnector,
    metaArtefactRepository     = metaArtefactRepository,
    derivedViewsService        = derivedViewsService
  ):
    override protected def buildSqsClient(): SqsAsyncClient =
      TestMetaArtefactUpdateHandler.client

    def handle(message: Message): Future[MessageAction] =
      processMessage(message)
