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

package uk.gov.hmrc.servicedependencies.service

import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.{never, verify, verifyNoInteractions, when}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.servicedependencies.model.Version
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MetaArtefactBulkCleanupServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  "cleanupDeletions" should {
    "dry run without deleting meta artefacts, refreshing views, or deleting SQS messages" in {
      val boot = Boot.init(
        Seq(
          deletionMessage("message-1", "affinity-group", "0.65.0-SNAPSHOT"),
          deletionMessage("message-2", "affinity-group", "0.66.0-SNAPSHOT")
        )
      )

      val result = boot.service.cleanupDeletions(maxMessages = 1000, dryRun = true).futureValue

      result.dryRun               shouldBe true
      result.inspected            shouldBe 2
      result.matched              shouldBe 2
      result.skipped              shouldBe 0
      result.failed               shouldBe 0
      result.deletedMetaArtefacts shouldBe 0
      result.deletedSqsMessages   shouldBe 0
      result.affectedRepositories shouldBe Seq("affinity-group")
      boot.deletedMessages        shouldBe empty
      verifyNoInteractions(boot.metaArtefactRepository)
      verifyNoInteractions(boot.derivedViewsService)
    }

    "bulk delete grouped meta deletion messages and refresh each repository once" in {
      val boot = Boot.init(
        Seq(
          deletionMessage("message-1", "affinity-group", "0.65.0-SNAPSHOT"),
          deletionMessage("message-2", "affinity-group", "0.66.0-SNAPSHOT"),
          deletionMessage("message-3", "another-service", "1.2.3")
        )
      )

      when(boot.metaArtefactRepository.deleteMany(eqTo("affinity-group"), eqTo(Seq(Version("0.65.0-SNAPSHOT"), Version("0.66.0-SNAPSHOT")))))
        .thenReturn(Future.unit)
      when(boot.metaArtefactRepository.deleteMany(eqTo("another-service"), eqTo(Seq(Version("1.2.3")))))
        .thenReturn(Future.unit)
      when(boot.derivedViewsService.updateDerivedViews(eqTo("affinity-group"))(using eqTo(boot.headerCarrier)))
        .thenReturn(Future.unit)
      when(boot.derivedViewsService.updateDerivedViews(eqTo("another-service"))(using eqTo(boot.headerCarrier)))
        .thenReturn(Future.unit)

      val result = boot.service.cleanupDeletions(maxMessages = 1000, dryRun = false).futureValue

      result.dryRun               shouldBe false
      result.inspected            shouldBe 3
      result.matched              shouldBe 3
      result.skipped              shouldBe 0
      result.failed               shouldBe 0
      result.deletedMetaArtefacts shouldBe 3
      result.deletedSqsMessages   shouldBe 3
      result.affectedRepositories should contain theSameElementsInOrderAs Seq("affinity-group", "another-service")
      boot.deletedMessages.map(_.messageId) should contain theSameElementsAs Seq("message-1", "message-2", "message-3")
      verify(boot.derivedViewsService).updateDerivedViews(eqTo("affinity-group"))(using eqTo(boot.headerCarrier))
      verify(boot.derivedViewsService).updateDerivedViews(eqTo("another-service"))(using eqTo(boot.headerCarrier))
    }

    "skip non-meta-deletion messages and leave them on SQS" in {
      val boot = Boot.init(
        Seq(
          creationMessage("message-1", "affinity-group", "0.65.0-SNAPSHOT"),
          deletionMessage("message-2", "slug", "affinity-group", "0.65.0-SNAPSHOT"),
          deletionMessage("message-3", "meta", "affinity-group", "0.66.0-SNAPSHOT")
        )
      )

      when(boot.metaArtefactRepository.deleteMany(eqTo("affinity-group"), eqTo(Seq(Version("0.66.0-SNAPSHOT")))))
        .thenReturn(Future.unit)
      when(boot.derivedViewsService.updateDerivedViews(eqTo("affinity-group"))(using eqTo(boot.headerCarrier)))
        .thenReturn(Future.unit)

      val result = boot.service.cleanupDeletions(maxMessages = 1000, dryRun = false).futureValue

      result.inspected            shouldBe 3
      result.matched              shouldBe 1
      result.skipped              shouldBe 2
      result.failed               shouldBe 0
      result.deletedMetaArtefacts shouldBe 1
      result.deletedSqsMessages   shouldBe 1
      boot.deletedMessages.map(_.messageId) shouldBe Seq("message-3")
      verify(boot.metaArtefactRepository, never())
        .deleteMany(eqTo("slug"), eqTo(Seq(Version("0.65.0-SNAPSHOT"))))
    }
  }

  private def deletionMessage(messageId: String, name: String, version: String): Message =
    deletionMessage(messageId, "meta", name, version)

  private def deletionMessage(messageId: String, jobType: String, name: String, version: String): Message =
    message(
      messageId,
      s"""{"type":"deletion","jobType":"$jobType","name":"$name","version":"$version","url":"https://artefacts/$name-$version.meta.tgz"}"""
    )

  private def creationMessage(messageId: String, name: String, version: String): Message =
    message(
      messageId,
      s"""{"type":"creation","jobType":"meta","name":"$name","version":"$version","url":"https://artefacts/$name-$version.meta.tgz"}"""
    )

  private def message(messageId: String, body: String): Message =
    Message
      .builder()
      .messageId(messageId)
      .receiptHandle(s"$messageId-receipt")
      .body(body)
      .build()

  case class Boot(
    service               : TestMetaArtefactBulkCleanupService,
    metaArtefactRepository: MetaArtefactRepository,
    derivedViewsService   : DerivedViewsService,
    deletedMessages       : ListBuffer[Message],
    headerCarrier         : uk.gov.hmrc.http.HeaderCarrier
  )

  object Boot {
    def init(messages: Seq[Message]): Boot = {
      val metaArtefactRepository = mock[MetaArtefactRepository]
      val derivedViewsService    = mock[DerivedViewsService]
      val deletedMessages        = ListBuffer.empty[Message]
      val headerCarrier          = uk.gov.hmrc.http.HeaderCarrier()
      val service                = TestMetaArtefactBulkCleanupService(
        messages,
        deletedMessages,
        headerCarrier,
        metaArtefactRepository,
        derivedViewsService
      )

      Boot(service, metaArtefactRepository, derivedViewsService, deletedMessages, headerCarrier)
    }
  }

  class TestMetaArtefactBulkCleanupService(
    messages              : Seq[Message],
    deletedMessages       : ListBuffer[Message],
    override val headerCarrier: uk.gov.hmrc.http.HeaderCarrier,
    metaArtefactRepository: MetaArtefactRepository,
    derivedViewsService   : DerivedViewsService
  ) extends MetaArtefactBulkCleanupService(
    Configuration.empty,
    metaArtefactRepository,
    derivedViewsService,
    mock[ApplicationLifecycle]
  ) {
    private var pendingBatches = List(messages, Seq.empty[Message])

    override protected def receiveMessages(maxNumberOfMessages: Int): Future[Seq[Message]] = {
      val next = pendingBatches.headOption.getOrElse(Seq.empty)
      pendingBatches = pendingBatches.drop(1)
      Future.successful(next.take(maxNumberOfMessages))
    }

    override protected def deleteMessage(message: Message): Future[Unit] = {
      deletedMessages += message
      Future.unit
    }
  }
}
