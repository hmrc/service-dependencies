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
import java.time.Instant

import javax.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import uk.gov.hmrc.servicedependencies.Github
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.model._

@Singleton
class GithubConnector @Inject() (github: Github) {

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def toMongoRepositoryDependencies(results: Map[(String, String), Option[Version]]): Seq[MongoRepositoryDependency] =
    results
      .collect { case ((name, group), Some(currentVersion)) =>
         MongoRepositoryDependency(name = name, group = group, currentVersion = currentVersion)
       }
      .toSeq

  def findLatestVersion(repoName: String): Option[Version] =
    github.findLatestVersion(repoName)

  def buildDependencies(repo: RepositoryInfo, curatedDeps: CuratedDependencyConfig): Option[MongoRepositoryDependencies] =
    github.findVersionsForMultipleArtifacts(repo.name, curatedDeps)
      .right
      .map(searchResults =>
        MongoRepositoryDependencies(
          repositoryName        = repo.name
        , libraryDependencies   = toMongoRepositoryDependencies(searchResults.libraries)
        , sbtPluginDependencies = toMongoRepositoryDependencies(searchResults.sbtPlugins)
        , otherDependencies     = toMongoRepositoryDependencies(searchResults.others)
        , updateDate            = Instant.now()
        )
      ) match {
        case Left(errorMessage) =>
          logger.error(s"Skipping dependencies update for ${repo.name}, reason: $errorMessage")
          None
        case Right(results) =>
          logger.debug(s"Github search returned these results for ${repo.name}: $results")
          Some(results)
    }
}
