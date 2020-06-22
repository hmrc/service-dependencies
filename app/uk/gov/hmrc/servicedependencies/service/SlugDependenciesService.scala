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

import javax.inject.{Inject, Singleton}
import play.api.cache.AsyncCacheApi
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.Dependency
import uk.gov.hmrc.servicedependencies.model.{MongoLatestVersion, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.LatestVersionRepository

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, SECONDS}


@Singleton
class SlugDependenciesService @Inject()(
  slugInfoService          : SlugInfoService
, serviceDependenciesConfig: ServiceDependenciesConfig
, latestVersionRepository  : LatestVersionRepository
, serviceConfigsConnector  : ServiceConfigsConnector
, cache                    : AsyncCacheApi
)(implicit ec: ExecutionContext
) {

  private val cacheDuration = Duration.create(60, SECONDS)

  private def cachedLatestVersions(): Future[Seq[MongoLatestVersion]] = {
    cache.getOrElseUpdate(s"SlugDependenciesService.latestVersion", cacheDuration) {
      latestVersionRepository.getAllEntries
    }
  }

  // warm cache
  cachedLatestVersions()

  private lazy val curatedDependencyConfig: CuratedDependencyConfig =
    serviceDependenciesConfig.curatedDependencyConfig

  /*
   * We may want to evolve the model - but for this initial version we reuse the existing Dependency definition.
   */
  def curatedLibrariesOfSlug(name: String, flag: SlugInfoFlag): Future[Option[List[Dependency]]] =
    slugInfoService.getSlugInfo(name, flag).flatMap {
      case None           => Future.successful(None)
      case Some(slugInfo) => curatedLibrariesOfSlugInfo(slugInfo).map(Some.apply)
    }

  private def curatedLibrariesOfSlugInfo(slugInfo: SlugInfo): Future[List[Dependency]] =
    for {
      latestVersions <- cachedLatestVersions()
      bobbyRules     <- serviceConfigsConnector.getBobbyRules
      dependencies   =  slugInfo
                          .dependencies
                          .flatMap { slugDependency =>
                              val latestVersion =
                                latestVersions
                                  .find(v => v.group == slugDependency.group && v.name == slugDependency.artifact)
                                  .map(_.version)
                              Version.parse(slugDependency.version).map { currentVersion =>
                                Dependency(
                                    name                = slugDependency.artifact
                                  , group               = slugDependency.group
                                  , currentVersion      = currentVersion
                                  , latestVersion       = latestVersion
                                  , bobbyRuleViolations = bobbyRules.violationsFor(
                                                              group   = slugDependency.group
                                                            , name    = slugDependency.artifact
                                                            , version = currentVersion
                                                            )
                                  )
                              }
                          }
      filtered       =  dependencies.filter(dependency =>
                            curatedDependencyConfig.allDependencies.exists(lib =>
                              lib.name  == dependency.name &&
                              lib.group == dependency.group
                            ) ||
                            dependency.bobbyRuleViolations.nonEmpty
                          )
    } yield filtered
}
