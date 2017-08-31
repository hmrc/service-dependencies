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

package uk.gov.hmrc.servicedependencies.config

import java.io.File
import java.nio.file.Path

import play.api.Play
import play.api.libs.json.Json
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig

import scala.concurrent.duration._
import scala.io.Source

trait CacheConfig {
  def cacheDuration: FiniteDuration
}

trait ReleasesConfig {
  def releasesServiceUrl: String
}

class ServiceDependenciesConfig(configPath: String) extends CacheConfig with ReleasesConfig with ServicesConfig {

  private val cacheDurationConfigPath = "cache.timeout.duration"
  private val githubOpenConfigKey = "github.open.api"
  private val githubEnterpriseConfigKey = "github.enterprise.api"
  private val releaseServiceUrlKey = "releases.api.url"
  private val targetArtifactsKey = "target.artifacts"
//  private val teamsAndRepositoriesServiceUrlKey = "teamsandrepositories.api.url"

  private val defaultTimeout = 1 day

  lazy val targetArtifact = optionalConfig(s"$targetArtifactsKey").getOrElse("sbt-plugin")

  lazy val curatedDependencyConfig: CuratedDependencyConfig = {
    val stream = getClass.getResourceAsStream(configPath)
    val json = try {  Json.parse(stream) } finally { stream.close() }
    json.as[CuratedDependencyConfig]
  }


  val buildFiles = Seq(
    "project/AppDependencies.scala", //!@ test this (the order of this being before build.sbt is important)
    "build.sbt",
    "project/MicroServiceBuild.scala",
    "project/FrontendBuild.scala",
    "project/StubServiceBuild.scala",
    "project/HmrcBuild.scala"
  )

  def cacheDuration: FiniteDuration = {
    Play.current.configuration.getMilliseconds(cacheDurationConfigPath).map(_.milliseconds).getOrElse(defaultTimeout)
  }

  lazy val releasesServiceUrl = optionalConfig(s"$releaseServiceUrlKey").get
  lazy val teamsAndRepositoriesServiceUrl: String = baseUrl("teams-and-repositories")//optionalConfig(teamsAndRepositoriesServiceUrlKey).get

  private val gitOpenConfig = (key: String) => optionalConfig(s"$githubOpenConfigKey.$key")
  private val gitEnterpriseConfig = (key: String) => optionalConfig(s"$githubEnterpriseConfigKey.$key")

  lazy val githubApiOpenConfig = option(gitOpenConfig).getOrElse(GitApiConfig.fromFile(s"${System.getProperty("user.home")}/.github/.credentials"))
  lazy val githubApiEnterpriseConfig = option(gitEnterpriseConfig).getOrElse(GitApiConfig.fromFile(s"${System.getProperty("user.home")}/.github/.githubenterprise"))

  private def optionalConfig(path: String) = Play.current.configuration.getString(s"$path")
  private def option(config: String => Option[String]): Option[GitApiConfig] =
    for {
      host <- config("host")
      user <- config("user")
      key <- config("key")
    } yield GitApiConfig(user, key, host)
}

case class GitApiConfig(user: String, key: String, apiUrl: String)

object GitApiConfig {
  def fromFile(configFilePath: String): GitApiConfig = {
    findGithubCredsInFile(new File(configFilePath).toPath).getOrElse(throw new RuntimeException(s"could not find github credential in file : $configFilePath"))
  }

  private def findGithubCredsInFile(file: Path): Option[GitApiConfig] = {
    val conf = new ConfigFile(file)

    for {
      user <- conf.get("user")
      token <- conf.get("token")
      apiUrl <- conf.get("api-url")
    } yield GitApiConfig(user, token, apiUrl)
  }
}

class ConfigFile(filePath: Path) {
  private val kvMap: Map[String, String] =
    try {
      Source.fromFile(filePath.toFile)
        .getLines().toSeq
        .map(_.split("="))
        .map { case Array(key, value) => key.trim -> value.trim}.toMap
    } catch {
      case e: Exception => Map.empty
    }

  def get(path: String) = kvMap.get(path)
}





