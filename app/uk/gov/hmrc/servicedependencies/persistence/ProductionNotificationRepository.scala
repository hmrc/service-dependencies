/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.persistence

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import play.api.libs.functional.syntax._
import play.api.libs.json._
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.{Filters, FindOneAndReplaceOptions}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicedependencies.persistence.ProductionNotificationRepository.NotificationLastRun
import java.time.Instant

@Singleton
class ProductionNotificationRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[NotificationLastRun](
    mongoComponent = mongoComponent,
    collectionName = "notificationLastRun",
    domainFormat   = NotificationLastRun.format,
    indexes        = Seq.empty,
    extraCodecs    = Seq.empty
):
  override lazy val requiresTtlIndex = false // single record replaced each time

  def setLastRunTime(lastRunTime: Instant): Future[Unit] =
    collection
      .findOneAndReplace(
        filter      = Filters.empty(),
        replacement = NotificationLastRun(lastRunTime),
        options     = FindOneAndReplaceOptions().upsert(true)
      )
      .toFutureOption()
      .map(_ => ())
      .recover:
        case error => throw RuntimeException(s"failed to update last run time ${error.getMessage()}", error)

  def getLastRunTime(): Future[Option[Instant]] =
    collection
      .find()
      .map(_.value)
      .headOption()
  

object ProductionNotificationRepository:
  case class NotificationLastRun(value: Instant) extends AnyVal

  object NotificationLastRun:
      val format: OFormat[NotificationLastRun] =
        import MongoJavatimeFormats.Implicits.jatInstantFormat
        Format.at[Instant](__ \ "lastRunTime").inmap[NotificationLastRun](NotificationLastRun.apply, _.value)
