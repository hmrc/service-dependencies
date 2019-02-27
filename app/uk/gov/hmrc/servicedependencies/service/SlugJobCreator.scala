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

import java.time.Period

import akka.stream.Materializer
import javax.inject.{Inject, Singleton}
import org.joda.time.{Duration, Instant}
import play.api.Logger
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.connector.ArtifactoryConnector
import uk.gov.hmrc.servicedependencies.model.{NewSlugParserJob, Version}
import uk.gov.hmrc.servicedependencies.persistence.{SlugJobLastRunRepository, SlugParserJobsRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

@Singleton
class SlugJobCreator @Inject()(
  conn            : ArtifactoryConnector,
  jobsRepo        : SlugParserJobsRepository,
  jobRunRepo      : SlugJobLastRunRepository,
  schedulerConfigs: SchedulerConfigs)(
  implicit val materializer: Materializer) {

  import ExecutionContext.Implicits.global

  def runBackfill: Future[Unit] = {
    implicit val cmp = Ordering.Option(implicitly[Ordering[Version]]) // diverging implicit expansion?
    for {
      now       <- Future(Instant.now)
      jobs      <- conn.findSlugsForBackFill(now)
      grouped   =  jobs.groupBy(j => SlugParser.extractSlugNameFromUri(j.slugUri).getOrElse(""))
                     .mapValues(_.sortBy(f => SlugParser.extractVersionFromUri(f.slugUri)))
      _         =  grouped.foreach { case (k, v) =>
                     Logger.debug(s"backfill identified service=$k, versions=${v.map(s => SlugParser.extractVersionFromUri(s.slugUri)).collect{case Some(v) => v}} (only downloading the last))")
                   }
      latest    =  grouped.values.map(_.last) // last since we sorted by version
      _         <- Future.sequence(latest.map(jobsRepo.add))
      _         <- jobRunRepo.setLastRun(now)
    } yield ()
  }

  def run(): Future[Unit] =
    for {
      nextSince <- Future(Instant.now)
      lastRun   <- jobRunRepo.getLastRun
      since     =  lastRun.getOrElse {
                     val default = Instant.now.minus(Duration.millis(schedulerConfigs.slugJobCreator.frequency().toMillis))
                     Logger.warn(s"This is the first run of SlugJobCreator - you may want to backfill data before $default")
                     default
                   }
      _         =  Logger.info(s"creating slug jobs from artefactory: since=$since")
      slugJobs  <- conn.findAllSlugsSince(since)
      _         <- Future.sequence(slugJobs.map(jobsRepo.add))
      _         <- jobRunRepo.setLastRun(nextSince)
    } yield ()
}
