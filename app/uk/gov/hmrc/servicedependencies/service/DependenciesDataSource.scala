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

package uk.gov.hmrc.servicedependencies.service

import com.google.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.connector.{GithubConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.RepositoryLibraryDependenciesRepository
import uk.gov.hmrc.time.DateTimeUtils
import scala.concurrent.Future

@Singleton
class DependenciesDataSource @Inject()(
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  config: ServiceDependenciesConfig,
  github: GithubConnector,
  repoLibDepRepository: RepositoryLibraryDependenciesRepository) {


  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def now = DateTimeUtils.now

  def prepDependency(repo: RepositoryInfo,
                        curatedDependencyConfig: CuratedDependencyConfig,
                        currentDeps: Seq[MongoRepositoryDependencies],
                        force: Boolean): Option[MongoRepositoryDependencies] = {

    if (!force && lastUpdated(repo.name, currentDeps).isAfter(repo.lastUpdatedAt)) {
      logger.debug(s"No changes for repository (${repo.name}). Skipping....")
      None
    } else {
      logger.debug(s"building repo for ${repo.name}")
      github.buildDependencies(repo, curatedDependencyConfig)
    }

  }

  private def lastUpdated(repoName: String, currentDeps: Seq[MongoRepositoryDependencies]): DateTime = {
    currentDeps.find(_.repositoryName == repoName)
      .map(_.updateDate)
      .getOrElse(new DateTime(0))
  }


  private def serialiseFutures[A, B](l: Iterable[A])(fn: A => Future[B]): Future[Seq[B]] =
    l.foldLeft(Future(List.empty[B])) { (previousFuture, next) ⇒
      for {
        previousResults ← previousFuture
        next ← fn(next)
      } yield previousResults :+ next
    }

  def persistDependenciesForAllRepositories(
    curatedDependencyConfig: CuratedDependencyConfig,
    currentDependencyEntries: Seq[MongoRepositoryDependencies],
    force: Boolean = false)(implicit hc: HeaderCarrier): Future[Seq[MongoRepositoryDependencies]] = {

    teamsAndRepositoriesConnector
      .getAllRepositoryInfos()
      .map(repos => {
        logger.debug(s"loading dependencies for ${repos.length} repositories")
        repos.flatMap(r => prepDependency(r, curatedDependencyConfig, currentDependencyEntries, force))
      })
      .flatMap(serialiseFutures(_)(repo => repoLibDepRepository.update(repo)))
  }


  def getLatestSbtPluginVersions(sbtPlugins: Seq[SbtPluginConfig]): Seq[SbtPluginVersion] =
    sbtPlugins.flatMap(github.findLatestSbtPluginVersion)

  def getLatestLibrariesVersions(libraries: Seq[String]): Seq[LibraryVersion] =
    libraries.flatMap(github.findLatestLibraryVersion)

}
