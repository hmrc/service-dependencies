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

package uk.gov.hmrc.servicedependencies.service

import java.time.Instant

import cats.implicits._
import com.google.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, LibraryConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.connector.{GithubConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.RepositoryLibraryDependenciesRepository

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependenciesDataSource @Inject()(
    teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
  , config                       : ServiceDependenciesConfig
  , githubConnector              : GithubConnector
  , repoLibDepRepository         : RepositoryLibraryDependenciesRepository
  )(implicit ec: ExecutionContext
  ) {
  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def now: Instant = Instant.now()

  def buildDependency(
      repo                   : RepositoryInfo
    , curatedDependencyConfig: CuratedDependencyConfig
    , currentDeps            : Seq[MongoRepositoryDependencies]
    , force                  : Boolean
    ): Option[MongoRepositoryDependencies] =
    if (!force && lastUpdated(repo.name, currentDeps).isAfter(repo.lastUpdatedAt)) {
      logger.debug(s"No changes for repository (${repo.name}). Skipping....")
      None
    } else {
      logger.warn(s"building repo for ${repo.name}")
      githubConnector.buildDependencies(repo, curatedDependencyConfig)
    }


  private def lastUpdated(repoName: String, currentDeps: Seq[MongoRepositoryDependencies]): Instant =
    currentDeps.find(_.repositoryName == repoName)
      .map(_.updateDate)
      .getOrElse(Instant.EPOCH)


  def persistDependenciesForAllRepositories(
    curatedDependencyConfig : CuratedDependencyConfig
  , currentDependencyEntries: Seq[MongoRepositoryDependencies]
  , force                   : Boolean                          = false
  )(implicit hc: HeaderCarrier): Future[Seq[MongoRepositoryDependencies]] =
    for {
      repos <- teamsAndRepositoriesConnector.getAllRepositories()
      res   <- repos.toList.traverse { repo =>
                 buildDependency(repo, curatedDependencyConfig, currentDependencyEntries, force)
                   .traverse(repoLibDepRepository.update)
               }
    } yield res.flatten
}
