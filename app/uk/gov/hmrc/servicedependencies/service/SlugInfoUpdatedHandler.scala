/*
 * Copyright 2022 HM Revenue & Customs
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
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import cats.data.{EitherT, OptionT}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.ArtefactReceivingConfig
import uk.gov.hmrc.servicedependencies.connector.ArtefactProcessorConnector

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

class SlugInfoUpdatedHandler @Inject()(
  config                    : ArtefactReceivingConfig,
  artefactProcessorConnector: ArtefactProcessorConnector,
  slugInfoService           : SlugInfoService,
  messageHandling           : SqsMessageHandling
)(implicit
   actorSystem : ActorSystem,
   materializer: Materializer,
   ec          : ExecutionContext
) extends Logging {

  private lazy val queueUrl = config.sqsSlugQueue
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

  if (config.isEnabled) {
    SqsSource(queueUrl, settings)(awsSqsClient)
      .mapAsync(10)(processMessage)
      .withAttributes(ActorAttributes.supervisionStrategy {
        case NonFatal(e) => logger.error(s"Failed to process sqs messages: ${e.getMessage}", e); Supervision.Restart
      })
      .runWith(SqsAckSink(queueUrl)(awsSqsClient))
  } else {
    logger.info("SlugInfoUpdatedHandler is disabled")
  }

  private def after[A](delay: FiniteDuration)(task: => Future[A]): Future[A] = {
    val promise = Promise[A]()
    actorSystem.scheduler.scheduleOnce(delay)(task.foreach(promise.success))
    promise.future
  }

  private def processMessage(message: Message): Future[MessageAction] = {
    logger.debug(s"Starting processing SlugInfo message with ID '${message.messageId()}'")
    (for {
       available <- EitherT.fromEither[Future](
                      Json.parse(message.body)
                        .validate(JobAvailable.reads)
                        .asEither.left.map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                    )
       _         <- EitherT.cond[Future](available.jobType == "slug", (), s"${available.jobType} was not 'slug'")
       slugInfo  <- EitherT.fromOptionF(
                      artefactProcessorConnector.getSlugInfo(available.name, available.version),
                      s"SlugInfo for name: ${available.name}, version: ${available.version} was not found"
                    )
       optMeta   <- // we don't go to metaArtefactRepository since it might not have been updated yet...
                    EitherT.liftF(
                      OptionT(artefactProcessorConnector.getMetaArtefact(slugInfo.name, slugInfo.version))
                        // try again after a delay, could be a race-condition in being processed
                        .orElseF(after(config.metaArtefactRetryDelay)(artefactProcessorConnector.getMetaArtefact(slugInfo.name, slugInfo.version)))
                        .value
                    )
       _         <- EitherT(
                      slugInfoService.addSlugInfo(slugInfo, optMeta)
                        .map(Right.apply)
                        .recover {
                          case e =>
                            val errorMessage = s"Could not store SlugInfo for message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version})"
                            logger.error(errorMessage, e)
                            Left(s"$errorMessage ${e.getMessage}")
                        }
                    )
     } yield slugInfo
    ).value.map {
      case Left(error) =>
        logger.error(error)
        MessageAction.Ignore(message)
      case Right(slugInfo) =>
        logger.info(s"SlugInfo message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version}) successfully processed.")
        MessageAction.Delete(message)
    }
  }
}
