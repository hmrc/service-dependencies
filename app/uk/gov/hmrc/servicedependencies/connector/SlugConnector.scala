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
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Sink
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, BufferedInputStream}
import javax.inject.Inject
import play.api.libs.ws.{WSClient, WSResponse}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig


class SlugConnector @Inject()(ws: WSClient, serviceConfiguration: ServiceDependenciesConfig){


  def downloadSlug(slugUri: String): Future[BufferedInputStream] = {

    implicit val system = ActorSystem("AS")
    implicit val materializer = ActorMaterializer()
    implicit val ex = ExecutionContext.Implicits.global

    val out = new ByteArrayOutputStream()

    val sink = akka.stream.scaladsl.Sink.foreach[akka.util.ByteString] { bytes =>
      out.write(bytes.toArray)
    }
    ws.url(slugUri).withMethod("GET").stream.map {
      _.bodyAsSource.runWith(sink).andThen {
        case result => out.close() // Close the stream whether there was an error or not
                       result.get  // Get the result or rethrow the error
      }
    }.map { _ =>
      new BufferedInputStream(new ByteArrayInputStream(out.toByteArray))
    }
  }
}
