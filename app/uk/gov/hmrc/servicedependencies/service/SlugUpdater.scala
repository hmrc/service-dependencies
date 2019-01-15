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
import uk.gov.hmrc.servicedependencies.model.MongoSlugParserJob
import uk.gov.hmrc.servicedependencies.persistence.SlugParserJobsRepository

import scala.concurrent.duration.Duration

@Singleton
class SlugUpdater @Inject() (conn: ArtifactoryConnector, repo: SlugParserJobsRepository, implicit val materializer: Materializer) {

  def update() : Unit = {

    val printSink = Sink.foreach(println)

    Logger.info("Checking artifactory....")
    Source.fromFuture(conn.findAllSlugs())
      .mapConcat(identity)
      .take(10)
      .throttle(1, Duration(2, "seconds"))
      .mapAsync(1)(r => conn.findAllSlugsForService(r.uri))
      .mapConcat(identity)
      .to(mongoSink)
      .run()
  }

  import scala.concurrent.ExecutionContext.Implicits.global
  private val mongoSink = Sink.foreachParallel[MongoSlugParserJob](1)(repo.add)


  Logger.info("updating....")
  update()

}
