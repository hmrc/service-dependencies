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

package uk.gov.hmrc.servicedependencies.connector

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Compression, StreamConverters}
import java.io.InputStream

import javax.inject.Inject
import play.api.Logger
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}


class GzippedResourceConnector @Inject()(
             ws          : WSClient)(
    implicit actorSystem : ActorSystem,
             materializer: Materializer){

  /** @param resourceUrl the url pointing to gzipped resource
    * @return an uncompressed InputStream, which will close when it reaches the end
    */
  def openGzippedResource(resourceUrl: String): Future[InputStream] = {
    Logger.debug(s"downloading $resourceUrl")

    import ExecutionContext.Implicits.global

    ws.url(resourceUrl).withMethod("GET").withRequestTimeout(Duration.Inf).stream.map { resp =>
      resp.bodyAsSource.async.via(Compression.gunzip()).runWith(StreamConverters.asInputStream(readTimeout = 20.hour))
    }
  }
}
