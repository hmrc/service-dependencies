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

import cats.data.EitherT
import org.apache.pekko.actor.ActorSystem
import play.api.Configuration
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.ArtefactProcessorConnector
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository
import uk.gov.hmrc.servicedependencies.service.DerivedViewsService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetaArtefactUpdateHandler @Inject()(
  configuration             : Configuration,
  artefactProcessorConnector: ArtefactProcessorConnector,
  metaArtefactRepository    : MetaArtefactRepository,
  derivedViewsService       : DerivedViewsService
)(implicit
  actorSystem               : ActorSystem,
  ec                        : ExecutionContext
) extends SqsConsumer(
  name                      = "MetaArtefact"
, config                    = SqsConfig("aws.sqs.meta", configuration)
)(actorSystem, ec) {

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  override protected def processMessage(message: Message): Future[MessageAction] = {
    logger.debug(s"Starting processing MetaArtefact message with ID '${message.messageId()}'")
    (for {
       payload <- EitherT.fromEither[Future](
                    Json.parse(message.body)
                      .validate(MessagePayload.reads)
                      .asEither.left.map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                  )
       action  <- payload match {
                    case available: MessagePayload.JobAvailable =>
                      for {
                        _    <- EitherT.cond[Future](available.jobType == "meta", (), s"${available.jobType} was not 'meta'")
                        meta <- EitherT.fromOptionF(
                                  artefactProcessorConnector.getMetaArtefact(available.name, available.version)
                                , s"MetaArtefact for name: ${available.name}, version: ${available.version} was not found"
                                )
                        _    <- recoverFutureInEitherT(
                                  metaArtefactRepository.put(meta)
                                , errorMessage = s"Could not store MetaArtefact for message with ID '${message.messageId()}' (${meta.name} ${meta.version})"
                                )
                        _    <- EitherT.right[String](derivedViewsService.updateDerivedViews(available.name))
                      } yield {
                        logger.info(s"MetaArtefact available message with ID '${message.messageId()}' (${meta.name} ${meta.version}) successfully processed.")
                        MessageAction.Delete(message)
                      }
                    case deleted: MessagePayload.JobDeleted =>
                      for {
                        _ <- EitherT.cond[Future](deleted.jobType == "meta", (), s"${deleted.jobType} was not 'meta'")
                        _ <- recoverFutureInEitherT(
                               metaArtefactRepository.delete(deleted.name, deleted.version)
                             , errorMessage = s"Could not delete MetaArtefact for message with ID '${message.messageId()}' (${deleted.name} ${deleted.version})"
                             )
                        _ <- EitherT.right[String](derivedViewsService.updateDerivedViews(deleted.name))
                      } yield {
                        logger.info(s"MetaArtefact deleted message with ID '${message.messageId()}' (${deleted.name} ${deleted.version}) successfully processed.")
                        MessageAction.Delete(message)
                      }
                  }
     } yield action
    ).value.map {
      case Left(error) =>
        logger.error(error)
        MessageAction.Ignore(message)
      case Right(action) =>
        action
    }
  }

  private def recoverFutureInEitherT[A](f: Future[A], errorMessage: String) = EitherT(
    f.map(Right.apply)
     .recover {case e =>
       logger.error(errorMessage, e)
       Left(s"$errorMessage ${e.getMessage}")
     }
  )
}
