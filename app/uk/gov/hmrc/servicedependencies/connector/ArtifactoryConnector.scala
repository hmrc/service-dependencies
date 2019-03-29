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

import java.util.concurrent.Executors

import javax.inject.{Inject, Singleton}
import org.joda.time.{Duration, Instant}
import play.api.Logger
import play.api.libs.json._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.model.{ArtifactoryChild, ArtifactoryRepo}
import uk.gov.hmrc.servicedependencies.model.{NewSlugParserJob, SlugInfo, Version}
import uk.gov.hmrc.servicedependencies.service.SlugParser

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ArtifactoryConnector @Inject()(http: HttpClient, config: ServiceDependenciesConfig) {

  import ArtifactoryConnector._
  import ArtifactoryRepo._

  val artifactoryRoot = s"${config.artifactoryBase}/api/storage/webstore/slugs"
  val webstoreRoot    = s"${config.webstoreBase}/slugs"

  implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(1))

  /**
    * Enumerate all slugs in webstore
    */
  def findAllServices(): Future[List[ArtifactoryChild]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier(authorization)
    http.GET[ArtifactoryRepo](artifactoryRoot).map(_.children.filter(_.folder).toList)
  }

  def findSlugsForBackFill(now: Instant = Instant.now()): Future[List[NewSlugParserJob]] =
    Future.sequence(
        // query has been broken into 60 day blocks to avoid timeouts
        // TODO: review timeout after artifactory db upgrade
        Range.inclusive(0, 6 * 5)
          .map(i =>
            findAllSlugsSince(
              from = now.minus(Duration.standardDays(60 * (1 + i))),
              to   = now.minus(Duration.standardDays(60 * i)))))
      .map(_.flatten.toList)


  def findAllSlugsSince(from: Instant, to: Instant = Instant.now()): Future[List[NewSlugParserJob]] = {
    Logger.info(s"finding all slugs since $from from artifactory")

    val endpoint = s"${config.artifactoryBase}/api/search/creation?from=${from.getMillis}&to=${to.getMillis}&repo=webstore-local"
    implicit val hc: HeaderCarrier = HeaderCarrier(authorization)

    http.GET[JsObject](endpoint)
      .map { json =>
        (json \\ "uri")
          .map(_.as[String])
          .filter(_.startsWith(s"${config.artifactoryBase}/api/storage/webstore-local/slugs/"))
          .filter(uri => uri.endsWith(".tgz") || uri.endsWith(".tar.gz"))
          .map(ArtifactoryConnector.toDownloadURL)
          .map(NewSlugParserJob.apply)
          .toList
      }
  }

  private lazy val authorization : Option[Authorization] =
    for {
      user  <- config.artifactoryUser
      pwd   <- config.artifactoryPwd
      value =  java.util.Base64.getEncoder.encodeToString(s"$user:$pwd".getBytes)
    } yield Authorization(s"Basic $value")
}


object ArtifactoryConnector {

  def toDownloadURL(url: String): String =
    url.replace("https://artefacts.", "https://webstore.")
       .replace("/artifactory/api/storage/webstore-local/", "/")
}
