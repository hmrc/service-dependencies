/*
 * Copyright 2020 HM Revenue & Customs
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

import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.model.Version

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ArtifactoryConnector @Inject()(
  http  : HttpClient
, config: ServiceDependenciesConfig
)(implicit ec: ExecutionContext
){
  private val headers: List[(String, String)] =
    config.artifactoryApiKey
      .map(key => List(("X-JFrog-Art-Api", key)))
      .getOrElse(List.empty)

  def findLatestVersion(
    group       : String
  , artefact    : String
  , scalaVersion: Option[String]
  ): Future[Option[Version]] = {
    implicit val hc = HeaderCarrier().withExtraHeaders(headers: _*)
    http.GET[Option[HttpResponse]](
        url         = s"${config.artifactoryBase}/api/search/latestVersion"
      , queryParams = Map( "g" -> group
                         , "a" -> (artefact + scalaVersion.map("_" + _).getOrElse(""))
                         ).toSeq
      )
      .map(_.map(_.body).flatMap(Version.parse))
  }

  def findLatestVersion(
    group       : String
  , artefact    : String
  ): Future[Option[Version]] =
    // We currently don't know the scalaVersion, so try without, then "2.11", "2.12"...
    List(None, Some("2.11"), Some("2.12"))
      .foldLeftM(None: Option[Version]) {
        case (None, scalaVersion) => findLatestVersion(group, artefact, scalaVersion)
        case (res , _           ) => Future.successful(res)
      }
}
