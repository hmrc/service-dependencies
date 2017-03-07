/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc

import _root_.play.{api => playapi}
import _root_.play.api.Logger
import _root_.play.api.Play.current
import _root_.play.api.libs.json.{JsValue, Json, Reads}
import _root_.play.api.libs.ws.{WS, WSResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object HttpClient {

  def get[T](url: String, header: (String, String)*)(implicit r: Reads[T]): Future[T] =
    getResponseBody(url, header).map { rsp =>
      Try {
        Json.parse(rsp).as[T]
      } match {
        case Success(a) => a
        case Failure(e) =>
          Logger.error(s"Error paring response failed body was: $rsp root url : $url")
          throw e
      }
    }

  def getWithParsing[T](url: String, header: List[(String, String)] = List())(bodyParser: JsValue => T): Future[T] =
    getResponseBody(url, header).map { rsp =>
      Try {
        bodyParser(Json.parse(rsp))
      } match {
        case Success(a) => a
        case Failure(e) =>
          Logger.error(s"Error paring response failed body was: $rsp root url : $url")
          throw e
      }
    }

  private def getResponseBody(url: String, header: Seq[(String, String)] = List()): Future[String] =
    withErrorHandling("GET", url)(header) {
      case s if s.status >= 200 && s.status < 300 => s.body
      case res =>
        throw new RuntimeException(s"Unexpected response status : ${res.status}  calling url : $url response body : ${res.body}")
    }

  private def withErrorHandling[T](method: String, url: String, body: Option[JsValue] = None)(headers: Seq[(String, String)])(f: WSResponse => T)(implicit ec: ExecutionContext): Future[T] =
    buildCall(method, url, body, headers).execute().transform(
      f,
      _ => throw new RuntimeException(s"Error connecting  $url")
    )

  private def buildCall(method: String, url: String, body: Option[JsValue] = None, headers: Seq[(String, String)] = List()) = {
    val req = WS.client.url(url)
      .withMethod(method)
      .withHeaders(headers: _*)

    body.map { b =>
      req.withBody(b)
    }.getOrElse(req)
  }

}
