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

package uk.gov.hmrc.servicedependencies

import akka.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import play.api.Logger
import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import org.joda.time.Duration
import play.api.{Configuration, Logger}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.DefaultDB
import uk.gov.hmrc.servicedependencies.connector.SlugConnector
import uk.gov.hmrc.servicedependencies.model.SlugDependencyInfo
import uk.gov.hmrc.servicedependencies.persistence.{SlugDependencyRepository, SlugParserJobsRepository}
import uk.gov.hmrc.servicedependencies.service.SlugParser
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.control.NonFatal

class SlugParseScheduler @Inject()(
  actorSystem             : ActorSystem,
  configuration           : Configuration,
  metrics                 : Metrics,
  reactiveMongoComponent  : ReactiveMongoComponent,
  slugParserJobsRepository: SlugParserJobsRepository,
  slugDependencyRepository: SlugDependencyRepository,
  slugConnector           : SlugConnector,
  applicationLifecycle    : ApplicationLifecycle) {

  import ExecutionContext.Implicits.global

  private val slugParseKey = "repositoryDependencies.slugParse.interval"

  lazy val slugParseInterval: FiniteDuration =
    Option(configuration.getMillis(slugParseKey))
      .map(_.milliseconds)
      .getOrElse(throw new RuntimeException(s"$slugParseKey not specified"))

  val cancellable = actorSystem.scheduler.schedule(1.minute, slugParseInterval) {
    Logger.info("Running slug parse")
    runSlugParserJobs()
  }
  applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))

  // TODO to be called from S3 notification too
  def runSlugParserJobs() =
    // TODO use thread-pool (or akka actor pool?)
    slugParserJobsRepository
      .getAllEntries
      .map(
        _.map {
          job => Logger.debug(s"processing $job")
                 (for {
                    slug <- slugConnector.downloadSlug(job.slugUri)
                    slvs =  SlugParser.parse(job.slugName, slug)
                    _    <- slugDependencyRepository.add(SlugDependencyInfo(
                              slugName     = job.slugName,
                              slugUri      = job.slugUri,
                              dependencies = slvs))
                    _    <- job.id
                              .map(slugParserJobsRepository.delete)
                              .getOrElse(sys.error("No id on job!")) // shouldn't happen
                  } yield ()
                 ).recover {
                    case NonFatal(e) => Logger.error(s"An error occurred processing slug parser job ${job.id}: ${e.getMessage}", e)
                  }
        })
      .recover {
        case NonFatal(e) => Logger.error(s"An error occurred processing slug parser jobs: ${e.getMessage}", e)
      }
}
