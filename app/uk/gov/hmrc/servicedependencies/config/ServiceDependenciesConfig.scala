/*
 * Copyright 2018 HM Revenue & Customs
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

import com.google.inject.{Inject, Singleton}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.githubclient.GitApiConfig
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

@Singleton
class ServiceDependenciesConfig @Inject()(configuration: Configuration,
                                          serviceConfig: ServicesConfig) {

  private val githubOpenConfigKey     = "github.open.api"
  private val releaseServiceUrlKey    = "releases.api.url"
  private val targetArtifactsKey      = "target.artifacts"

  lazy val targetArtifact: String                 = configuration.getOptional[String](s"$targetArtifactsKey").getOrElse("sbt-plugin")
  lazy val releasesServiceUrl: String             = configuration.get[String](s"$releaseServiceUrlKey")
  lazy val teamsAndRepositoriesServiceUrl: String = serviceConfig.baseUrl("teams-and-repositories")

  private val gitOpenConfig = (key: String) => configuration.getOptional[String](s"$githubOpenConfigKey.$key")

  lazy val localCredentialPath = s"${System.getProperty("user.home")}/.github/.credentials"

  lazy val githubApiOpenConfig =
    parse(gitOpenConfig).getOrElse(GitApiConfig.fromFile(localCredentialPath))


  private def parse(config: String => Option[String]): Option[GitApiConfig] =
    for {
      host <- config("host")
      user <- config("user")
      key  <- config("key")
    } yield GitApiConfig(user, key, host)

}
