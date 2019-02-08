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

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import javax.inject.{Inject, Singleton}
import org.joda.time.{Duration, Instant}
import play.api.Logger
import uk.gov.hmrc.servicedependencies.config.SchedulerConfig
import uk.gov.hmrc.servicedependencies.connector.ArtifactoryConnector
import uk.gov.hmrc.servicedependencies.persistence.{SlugJobLastRunRepository, SlugParserJobsRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class RateLimit(invocations: Int, perDuration: FiniteDuration)

@Singleton
class SlugJobCreator @Inject()(
  conn           : ArtifactoryConnector,
  jobsRepo       : SlugParserJobsRepository,
  jobRunRepo     : SlugJobLastRunRepository,
  schedulerConfig: SchedulerConfig)(
  implicit val materializer: Materializer) {

  import ExecutionContext.Implicits.global

  val rateLimit: RateLimit = RateLimit(1, 2.seconds)

  def runHistoric(from: Int = 0, limit: Option[Int]): Future[Unit] = {
    Logger.info(s"creating slug jobs from artefactory: from=$from, limit=$limit")
    Source.fromFuture(conn.findAllServices())
      .mapConcat(identity)
      .drop(from)
      .take(limit.getOrElse[Int](Int.MaxValue))
      .throttle(rateLimit.invocations, rateLimit.perDuration)
      .mapAsyncUnordered(1)(r => conn.findAllSlugsForService(r.uri))
      .mapConcat(identity)
      .mapAsyncUnordered(1)(jobsRepo.add)
      .runWith(Sink.ignore)
      .map(_ => ())
  }

  def run(): Future[Unit] =
    for {
      nextSince <- Future(Instant.now)
      lastRun   <- jobRunRepo.getLastRun
      since     =  lastRun.getOrElse {
                     val default = Instant.now.minus(Duration.millis(schedulerConfig.SlugJobCreator.interval.toMillis))
                     Logger.warn(s"This is the first run of SlugJobCreator - you may want to backfill data before $default")
                     default
                   }
      _         =  Logger.info(s"creating slug jobs from artefactory: since=$since")
      slugJobs  <- conn.findAllSlugsSince(since)
      _         <- Future.sequence(slugJobs.map(jobsRepo.add))
      _         <- jobRunRepo.setLastRun(nextSince)
    } yield ()
}
