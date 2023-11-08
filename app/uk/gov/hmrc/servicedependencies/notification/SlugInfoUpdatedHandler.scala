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

import cats.data.{EitherT, OptionT}
import org.apache.pekko.actor.ActorSystem
import play.api.Configuration
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.ArtefactProcessorConnector
import uk.gov.hmrc.servicedependencies.service.SlugInfoService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.FiniteDuration

@Singleton
class SlugInfoUpdatedHandler @Inject()(
  configuration             : Configuration,
  artefactProcessorConnector: ArtefactProcessorConnector,
  slugInfoService           : SlugInfoService
)(implicit
  actorSystem               : ActorSystem,
  ec                        : ExecutionContext
) extends SqsConsumer(
  name                      = "SlugInfo"
, config                    = SqsConfig("aws.sqs.slug", configuration)
)(actorSystem, ec) {

  private implicit val hc = HeaderCarrier()

  lazy val metaArtefactRetryDelay: FiniteDuration =
    configuration.get[FiniteDuration]("aws.sqs.slug.retryDelay")

  lazy val metaArtefactRetryTimes: Int =
    configuration.get[Int]("aws.sqs.slug.retryTimes")

  private def after[A](delay: FiniteDuration)(task: => Future[A]): Future[A] = {
    val promise = Promise[A]()
    actorSystem.scheduler.scheduleOnce(delay)(task.foreach(promise.success))
    promise.future
  }

  private def attempt[A](delay: FiniteDuration, times: Int)(f: () => Future[Option[A]]): Future[Option[A]] =
    if (times > 0)
      OptionT(f())
        .orElse(OptionT(after(delay)(attempt(delay, times - 1)(f))))
        .value
    else
      Future.successful(None)

  override protected def processMessage(message: Message): Future[MessageAction] = {
    logger.debug(s"Starting processing SlugInfo message with ID '${message.messageId()}'")
    (for {
       payload <- EitherT.fromEither[Future](
                      Json.parse(message.body)
                        .validate(MessagePayload.reads)
                        .asEither.left.map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                    )
       action  <- payload match {
                    case available: MessagePayload.JobAvailable =>
                      for {
                        _         <- EitherT.cond[Future](available.jobType == "slug", (), s"${available.jobType} was not 'slug'")
                        slugInfo  <- EitherT.fromOptionF(
                                       artefactProcessorConnector.getSlugInfo(available.name, available.version),
                                       s"SlugInfo for name: ${available.name}, version: ${available.version} was not found"
                                     )
                        optMeta   <- // we don't go to metaArtefactRepository since it might not have been updated yet...
                                     EitherT.liftF(
                                       // try a few times, the meta-artefact may not have been processed yet
                                       // or it may just not be available (e.g. java slugs)
                                       attempt(delay = metaArtefactRetryDelay, times = metaArtefactRetryTimes)(() =>
                                         artefactProcessorConnector.getMetaArtefact(slugInfo.name, slugInfo.version)
                                       )
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
                      } yield {
                        logger.info(s"SlugInfo message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version}) successfully processed.")
                        MessageAction.Delete(message)
                      }
                    case deleted: MessagePayload.JobDeleted =>
                      for {
                        _ <- EitherT.cond[Future](deleted.jobType == "slug", (), s"${deleted.jobType} was not 'slug'")
                        _ <- EitherT(
                               slugInfoService.deleteSlugInfo(deleted.name, deleted.version)
                                 .map(Right.apply)
                                 .recover {
                                   case e =>
                                     val errorMessage = s"Could not delete SlugInfo for message with ID '${message.messageId()}' (${deleted.name} ${deleted.version})"
                                     logger.error(errorMessage, e)
                                     Left(s"$errorMessage ${e.getMessage}")
                                 }
                             )
                       } yield {
                         logger.info(s"SlugInfo deleted message with ID '${message.messageId()}' (${deleted.name} ${deleted.version}) successfully processed.")
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
}
