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

  private lazy val curatedLibraries = curatedDependencyConfigProvider.curatedDependencyConfig.libraries.toSet

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
      latestVersionByName  <- libraryVersionRepository.getAllEntries.map(toLatestVersionByName)
      curatedDependencies  =  slugInfo.dependencies.filter(slugDependency => curatedLibraries.contains(slugDependency.artifact))
      enrichedDependencies <- serviceConfigsService.getDependenciesWithBobbyRules(curatedDependencies
                                  .map { slugDependency =>
                                      Dependency(
                                          name                = slugDependency.artifact
                                        , currentVersion      = Version(slugDependency.version)  // TODO this is unsafe
                                        , latestVersion       = // TODO: also a bit of a hack until we do latest version no correctly
                                                                if (slugDependency.group == "uk.gov.hmrc") latestVersionByName.get(slugDependency.artifact) else None
                                        , bobbyRuleViolations = Nil
                                        )
                                     }
                                   )
    } yield enrichedDependencies

  private def toLatestVersionByName(latestVersions: Seq[MongoLibraryVersion]): Map[String, Version] =
    (for {
       library        <- latestVersions
       libraryVersion <- library.version
     } yield library.libraryName -> libraryVersion
    ).toMap
}
