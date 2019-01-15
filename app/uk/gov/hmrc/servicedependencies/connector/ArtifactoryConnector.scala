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
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.model.{ArtifactoryChild, ArtifactoryRepo}
import uk.gov.hmrc.servicedependencies.model.NewSlugParserJob

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
  def findAllSlugs(): Future[List[ArtifactoryChild]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = headers)
    http.GET[ArtifactoryRepo](artifactoryRoot).map(_.children.filter(_.folder).toList)
  }


  /**
    * Connect to artifactory and retrieve a list of all available slugs
    */
  def findAllSlugsForService(service: String): Future[List[NewSlugParserJob]] = {
    Logger.info(s"downloading $service from artifactory")
    implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = headers)
    http.GET[ArtifactoryRepo](s"$artifactoryRoot$service")
      .map(_.children
        .filterNot(_.folder)
        .map(repo => convertToSlugParserJob(service, repo.uri, webstoreRoot)).toList)
  }

  private val headers : List[(String, String)] =
    config.artifactoryApiKey
      .map( key => List(("X-JFrog-Art-Api", key)))
      .getOrElse(List.empty)

}


object ArtifactoryConnector {

  def convertToSlugParserJob(serviceName: String, uri: String, webStoreRoot: String) : NewSlugParserJob = {
    NewSlugParserJob(s"$webStoreRoot$serviceName$uri")
  }

  def convertToWebStoreURL(url: String) : String = {
    val artifactoryPrefix = "https://artefacts."
    val webstorePrefix    = "https://webstore."
    val artifactorySuffix = "/artifactory/webstore"
    url.replace(artifactoryPrefix, webstorePrefix).replace(artifactorySuffix, "")
  }

}

