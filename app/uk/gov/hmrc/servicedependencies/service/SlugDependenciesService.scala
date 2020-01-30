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

import cats.implicits._
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.servicedependencies.config.CuratedDependencyConfigProvider
import uk.gov.hmrc.servicedependencies.config.model.LibraryConfig
import uk.gov.hmrc.servicedependencies.controller.model.Dependency
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, SlugDependency, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.LibraryVersionRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class SlugDependenciesService @Inject()(
  slugInfoService                : SlugInfoService,
  curatedDependencyConfigProvider: CuratedDependencyConfigProvider,
  libraryVersionRepository       : LibraryVersionRepository,
  serviceConfigsService          : ServiceConfigsService) {

  private lazy val curatedLibraries: Set[LibraryConfig] =
    curatedDependencyConfigProvider.curatedDependencyConfig.libraries.toSet

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
      latestVersions        <- libraryVersionRepository.getAllEntries
      curatedDependencies   =  slugInfo.dependencies
                                 .filter(slugDependency =>
                                   curatedLibraries.exists(lib => lib.name  == slugDependency.artifact &&
                                                                  lib.group == slugDependency.group
                                                          )
                                 )
      enrichedDependencies  <- serviceConfigsService.getDependenciesWithBobbyRules(curatedDependencies
                                  .map { slugDependency =>
                                      val latestVersion =
                                        latestVersions
                                          .find(v => v.group == slugDependency.group && v.name == slugDependency.artifact)
                                          .flatMap(_.version)
                                      Dependency(
                                          name                = slugDependency.artifact
                                        , group               = slugDependency.group
                                        , currentVersion      = Version(slugDependency.version)  // TODO this is unsafe
                                        , latestVersion       = latestVersion
                                        , bobbyRuleViolations = Nil
                                        )
                                     }
                                   )
    } yield enrichedDependencies
}
