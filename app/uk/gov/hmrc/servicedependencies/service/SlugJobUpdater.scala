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
import play.api.Logger
import uk.gov.hmrc.servicedependencies.connector.ArtifactoryConnector
import uk.gov.hmrc.servicedependencies.connector.model.ArtifactoryChild
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, NewSlugParserJob}
import uk.gov.hmrc.servicedependencies.persistence.SlugParserJobsRepository

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class RateLimit(invocations: Int, perDuration: FiniteDuration)

@Singleton
class SlugJobUpdater @Inject()(
  conn: ArtifactoryConnector,
  repo: SlugParserJobsRepository)(
  implicit val materializer: Materializer) {

  import ExecutionContext.Implicits.global

  val rateLimit: RateLimit = RateLimit(1, 2.seconds)

  def update(limit: Int = Int.MaxValue): Future[Unit] =
    Source.fromFuture(conn.findAllSlugs())
      .mapConcat(identity)
      .take(limit)
      .throttle(rateLimit.invocations, rateLimit.perDuration)
      .mapAsyncUnordered(1)(r => conn.findAllSlugsForService(r.uri))
      .mapConcat(identity)
      .mapAsyncUnordered(1) { job =>
        Logger.info(s"adding job $job")
        repo.add(job)
      }
      .runWith(Sink.ignore)
      .map(_ => ())


  def inWhiteList(c: ArtifactoryChild) : Boolean = {
    Logger.info(s"filtering ${c.uri}")
    repos.exists(r => c.uri.endsWith(r))
  }

  val repos = List(
    "/key-store",
    "/User-details",
    "/company-auth-frontend",
    "/multi-factor-authentication",
    "/poison-password",
    "/agent-usher",
    "/third-party-application",
    "/one-time-password",
    "/preferences",
    "/government-gateway-authentication",
    "/message-frontend",
    "/message",
    "/personal-details-validation",
    "/affinity-group",
    "/back-office-adapter",
    "/transaction-engine",
    "/paye-tax-calculator-frontend",
    "/bas-gateway",
    "/email"
  )
}
