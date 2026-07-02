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

import cats.implicits.*
import play.api.{Configuration, Logging}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{Json, Writes}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, Message, ReceiveMessageRequest}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.model.Version
import uk.gov.hmrc.servicedependencies.notification.{MessagePayload, SqsConfig}
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.util.Try

@Singleton
class MetaArtefactBulkCleanupService @Inject()(
  configuration         : Configuration,
  metaArtefactRepository: MetaArtefactRepository,
  derivedViewsService   : DerivedViewsService,
  applicationLifecycle  : ApplicationLifecycle
)(using
  ec                    : ExecutionContext
) extends Logging:

  import MetaArtefactBulkCleanupService.BulkCleanupResult

  protected val headerCarrier: HeaderCarrier =
    HeaderCarrier()

  private val config: SqsConfig =
    SqsConfig("aws.sqs.meta", configuration)

  protected def buildSqsClient(): SqsAsyncClient =
    SqsAsyncClient.builder().build()

  private lazy val awsSqsClient: SqsAsyncClient =
    buildSqsClient()

  applicationLifecycle.addStopHook: () =>
    Future.successful(awsSqsClient.close())

  protected def receiveMessages(maxNumberOfMessages: Int): Future[Seq[Message]] =
    awsSqsClient
      .receiveMessage(
        ReceiveMessageRequest
          .builder()
          .queueUrl(config.queueUrl.toString)
          .maxNumberOfMessages(maxNumberOfMessages)
          .waitTimeSeconds(0)
          .build()
      )
      .asScala
      .map(_.messages.asScala.toSeq)

  protected def deleteMessage(message: Message): Future[Unit] =
    awsSqsClient
      .deleteMessage(
        DeleteMessageRequest
          .builder()
          .queueUrl(config.queueUrl.toString)
          .receiptHandle(message.receiptHandle)
          .build()
      )
      .asScala
      .map(_ => ())

  def cleanupDeletions(maxMessages: Int, dryRun: Boolean): Future[BulkCleanupResult] =
    for
      messages         <- receiveUpTo(maxMessages)
      eventsAndSkipped = messages.map(toMetaDeletionEvent)
      events           = eventsAndSkipped.collect { case Right(event) => event }
      skipped          = eventsAndSkipped.collect { case Left(reason) => reason }
      result           <- {
                   if dryRun then
                     Future.successful(
                       BulkCleanupResult(
                         dryRun               = dryRun,
                         inspected            = messages.size,
                         matched              = events.size,
                         skipped              = skipped.size,
                         failed               = 0,
                         deletedMetaArtefacts = 0,
                         deletedSqsMessages   = 0,
                         affectedRepositories = events.map(_.name).distinct.sorted,
                         skippedSamples       = skipped.take(10),
                         failureSamples       = Seq.empty
                       )
                     )
                   else
                     processEvents(events)
                       .map: groupResults =>
                         BulkCleanupResult(
                           dryRun               = dryRun,
                           inspected            = messages.size,
                           matched              = events.size,
                           skipped              = skipped.size,
                           failed               = groupResults.map(_.failedMessages).sum,
                           deletedMetaArtefacts = groupResults.map(_.deletedMetaArtefacts).sum,
                           deletedSqsMessages   = groupResults.map(_.deletedSqsMessages).sum,
                           affectedRepositories = events.map(_.name).distinct.sorted,
                           skippedSamples       = skipped.take(10),
                           failureSamples       = groupResults.flatMap(_.failureSamples).take(10)
                         )
                 }
    yield result

  private def receiveUpTo(maxMessages: Int): Future[Seq[Message]] =
    def loop(remaining: Int, acc: Seq[Message]): Future[Seq[Message]] =
      if remaining <= 0 then
        Future.successful(acc)
      else
        receiveMessages(maxNumberOfMessages = Math.min(remaining, 10))
          .flatMap: messages =>
            if messages.isEmpty then Future.successful(acc)
            else loop(remaining - messages.size, acc ++ messages)

    loop(maxMessages, Seq.empty)

  private def processEvents(events: Seq[MetaDeletionEvent]): Future[Seq[GroupCleanupResult]] =
    events
      .groupBy(_.name)
      .toSeq
      .sortBy(_._1)
      .foldLeftM(Seq.empty[GroupCleanupResult]) { case (acc, (repoName, repoEvents)) =>
        processRepository(repoName, repoEvents).map(acc :+ _)
      }

  private def processRepository(repoName: String, events: Seq[MetaDeletionEvent]): Future[GroupCleanupResult] =
    val versions = events.map(_.version).distinct
    (for
      _                 <- metaArtefactRepository.deleteMany(repoName, versions)
      _                 <- derivedViewsService.updateDerivedViews(repoName)(using headerCarrier)
      sqsDeleteFailures <- events.foldLeftM(Seq.empty[String]): (failures, event) =>
                             deleteMessage(event.message)
                               .map(_ => failures)
                               .recover: e =>
                                 failures :+ s"Could not delete SQS message ${event.message.messageId()} for $repoName ${event.version}: ${e.getMessage}"
    yield
      GroupCleanupResult(
        deletedMetaArtefacts = events.size,
        deletedSqsMessages   = events.size - sqsDeleteFailures.size,
        failedMessages       = sqsDeleteFailures.size,
        failureSamples       = sqsDeleteFailures
      )
    ).recover: e =>
      logger.error(s"Could not bulk clean meta artefact deletions for $repoName", e)
      GroupCleanupResult(
        deletedMetaArtefacts = 0,
        deletedSqsMessages   = 0,
        failedMessages       = events.size,
        failureSamples       = Seq(s"Could not clean $repoName: ${e.getMessage}")
      )

  private def toMetaDeletionEvent(message: Message): Either[String, MetaDeletionEvent] =
    Try(Json.parse(message.body).validate(MessagePayload.reads).asEither)
      .toEither
      .left
      .map(e => s"Could not parse message ${message.messageId()}: ${e.getMessage}")
      .flatMap:
        case Left(errors) =>
          Left(s"Could not parse message ${message.messageId()}: $errors")
        case Right(MessagePayload.JobDeleted("meta", name, version, _)) =>
          Right(MetaDeletionEvent(message, name, version))
        case Right(MessagePayload.JobDeleted(jobType, name, version, _)) =>
          Left(s"Skipping message ${message.messageId()} for $name $version because jobType $jobType was not meta")
        case Right(_: MessagePayload.JobAvailable) =>
          Left(s"Skipping message ${message.messageId()} because type was not deletion")

  private case class MetaDeletionEvent(
    message: Message,
    name   : String,
    version: Version
  )

  private case class GroupCleanupResult(
    deletedMetaArtefacts: Int,
    deletedSqsMessages  : Int,
    failedMessages      : Int,
    failureSamples      : Seq[String]
  )

object MetaArtefactBulkCleanupService:

  case class BulkCleanupResult(
    dryRun              : Boolean,
    inspected           : Int,
    matched             : Int,
    skipped             : Int,
    failed              : Int,
    deletedMetaArtefacts: Int,
    deletedSqsMessages  : Int,
    affectedRepositories: Seq[String],
    skippedSamples      : Seq[String],
    failureSamples      : Seq[String]
  )

  object BulkCleanupResult:
    given writes: Writes[BulkCleanupResult] =
      Json.writes[BulkCleanupResult]
