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
import org.joda.time.Instant
import play.api.Logger
import play.api.libs.json._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.model.{ArtifactoryChild, ArtifactoryRepo}
import uk.gov.hmrc.servicedependencies.model.{NewSlugParserJob, SlugInfo}
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
    implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = xHeaders)
    http.GET[ArtifactoryRepo](artifactoryRoot).map(_.children.filter(_.folder).toList)
  }


  def findAllSlugsSince(from: Instant) : Future[List[NewSlugParserJob]] = {
    Logger.info(s"finding all slugs since $from")

    val endpoint = s"${config.artifactoryBase}/api/search/creation?from=${from.getMillis}&repo=webstore-local"
    implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = xHeaders)

    http.GET[JsObject](endpoint)
      .map { json =>
        (json \\ "uri")
          .map(_.as[String])
          .filter(_.startsWith(s"${config.artifactoryBase}/api/storage/webstore-local/slugs/"))
          .filter(uri => uri.endsWith(".tgz") || uri.endsWith(".tar.gz"))
          .map(ArtifactoryConnector.toDownloadURL)
          .map(url => NewSlugParserJob(url))
          .toList
      }
  }


  /**
    * Connect to artifactory and retrieve a list of all available slugs
    */
  def findAllSlugsForService(service: String): Future[List[NewSlugParserJob]] = {
    Logger.info(s"downloading $service from artifactory")
    implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = xHeaders)
    http.GET[ArtifactoryRepo](s"$artifactoryRoot$service")
      .map {
        _.children
          .filterNot(_.folder)
          .map(repo => convertToSlugParserJob(service, repo.uri, webstoreRoot))
          .toList
      }.map { l =>
        // for now, mark all as processed, except the latest version
        if (l.isEmpty) l
        else {
          val l2 = l.map(_.copy(processed = true))
          val (max, i) = l2.zipWithIndex.maxBy { j =>
            (for {
               x         <- SlugParser.extractVersionsFromUri(j._1.slugUri)
               (_, v, _) =  x
               v         <- SlugInfo.toLong(v)
             } yield v
            ).getOrElse(0L)
          }
          l2.updated(i, max.copy(processed = false))
        }
      }
  }

  private lazy val xHeaders : List[(String, String)] =
    config.artifactoryApiKey
      .map(key => List("X-JFrog-Art-Api" -> key))
      .getOrElse(List.empty)
}


object ArtifactoryConnector {

  def convertToSlugParserJob(serviceName: String, uri: String, webStoreRoot: String): NewSlugParserJob =
    NewSlugParserJob(
      slugUri   = s"$webStoreRoot$serviceName$uri",
      processed = false)

  def convertToWebStoreURL(url: String): String =
    url.replace("https://artefacts.", "https://webstore.")
       .replace("/artifactory/webstore", "")

  def toDownloadURL(url: String): String =
    url.replace("https://artefacts.", "https://webstore.")
       .replace("/artifactory/api/storage/webstore-local/", "/webstore/")
}
