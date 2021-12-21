/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.config

import java.io.File

import com.google.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.Json
import uk.gov.hmrc.githubclient.GitApiConfig
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig

import scala.concurrent.duration.Duration

@Singleton
class ServiceDependenciesConfig @Inject()(
  configuration: Configuration,
  serviceConfig: ServicesConfig
) {

  lazy val teamsAndRepositoriesServiceUrl: String =
    serviceConfig.baseUrl("teams-and-repositories")

  private def gitPath(gitFolder: String): String =
    s"${System.getProperty("user.home")}/.github/$gitFolder"

  private val host = configuration.getOptional[String]("github.open.api.host")
  private val user = configuration.getOptional[String]("github.open.api.user")
  private val key  = configuration.getOptional[String]("github.open.api.key")

  val githubApiOpenConfig: GitApiConfig =
    (user, key, host) match {
      case (Some(u), Some(k), Some(h)) => GitApiConfig(u, k, h)
      case (None, None, None) if new File(gitPath(".credentials")).exists() => GitApiConfig.fromFile(gitPath(".credentials"))
      case _ => GitApiConfig("user_not_set", "key_not_set", "https://hostnotset.com")
    }

  lazy val teamsAndRepositoriesCacheExpiration =
    configuration.get[Duration]("microservice.services.teams-and-repositories.cache.expiration")

  lazy val artifactoryBase: String          = configuration.get[String]("artifactory.url")
  lazy val artifactoryToken: Option[String] = configuration.getOptional[String]("artifactory.token")

  lazy val curatedDependencyConfig: CuratedDependencyConfig = {
    implicit val cdcr = CuratedDependencyConfig.reads
    val configFilePath =
      configuration.getOptional[String]("curated.config.path")
        .getOrElse(sys.error("config value not found: curated.config.path"))

    val stream = getClass.getResourceAsStream(configFilePath)
    val json = try {
      Json.parse(stream)
    } finally {
      stream.close()
    }
    json.as[CuratedDependencyConfig]
  }

  val githubRawUrl =
    configuration.get[String]("github.open.api.rawurl")
}
