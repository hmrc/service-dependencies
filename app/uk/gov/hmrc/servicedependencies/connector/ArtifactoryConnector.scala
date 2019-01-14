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
import javax.inject.Inject
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.model.ArtifactoryRepo

import scala.concurrent.ExecutionContext.Implicits.global

class ArtifactoryConnector @Inject()(http: HttpClient, config: ServiceDependenciesConfig){


  import ArtifactoryRepo._


  def findAllSlugs() = {
    implicit val hc = HeaderCarrier(otherHeaders = headers)
    http.GET[ArtifactoryRepo](config.artifactoryBase)

  }

  private val headers : List[(String, String)] =
    config.artifactoryApiKey
      .map( key => List(("X-JFrog-Art-Api", key)))
      .getOrElse(List.empty)

}


object ArtifactoryConnector {


  def convertToWebStoreURL(url: String) : String = {
    val artifactoryPrefix = "https://artefacts."
    val artifactorySuffix = "/artifactory/webstore"
    val webstorePrefix    = "https://webstore."
    url.replace(artifactoryPrefix, webstorePrefix).replace(artifactorySuffix, "")
  }


  def parseVersion(uri: String): Option[String] = {
    val regex = """_([\d-\w\.]+)_""".r
    regex.findFirstMatchIn(uri).map(_.group(1))
  }
}

