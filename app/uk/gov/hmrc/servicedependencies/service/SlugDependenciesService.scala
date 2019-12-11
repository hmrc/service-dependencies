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

package uk.gov.hmrc.servicedependencies.service

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.servicedependencies.config.CuratedDependencyConfigProvider
import uk.gov.hmrc.servicedependencies.controller.model.Dependency
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, SlugDependency, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.LibraryVersionRepository
import uk.gov.hmrc.servicedependencies.service.SlugDependenciesService.TargetVersion

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class SlugDependenciesService @Inject() (slugInfoService: SlugInfoService,
                                         curatedDependencyConfigProvider: CuratedDependencyConfigProvider,
                                         libraryVersionRepository: LibraryVersionRepository,
                                         serviceConfigsService: ServiceConfigsService) {

  private lazy val curatedLibraries = curatedDependencyConfigProvider.curatedDependencyConfig.libraries.toSet

  /*
   * We may want to evolve the model - but for this initial version we reuse the existing Dependency definition.
   */
  def curatedLibrariesOfSlug(name: String, atVersion: TargetVersion): Future[Option[List[Dependency]]] = {
    val futLatestVersionByName = libraryVersionRepository.getAllEntries.map(toLatestVersionByName)
    val futOptCuratedDependencies = retrieveSlugInfo(name, atVersion).map(toCuratedDependencies)
    for {
      latestVersionByName <- futLatestVersionByName
      optCuratedDependencies <- futOptCuratedDependencies
      enrichedDependencies <- enrichSlugDependencies(latestVersionByName.get, optCuratedDependencies)
    } yield enrichedDependencies
  }

  private def toLatestVersionByName(latestVersions: Seq[MongoLibraryVersion]): Map[String, Version] = {
    val nameVersionPairs = for {
      library <- latestVersions
      libraryVersion <- library.version
    } yield library.libraryName -> libraryVersion

    nameVersionPairs.toMap
  }

  private def retrieveSlugInfo(name: String, atVersion: TargetVersion): Future[Option[SlugInfo]] = {
    import SlugDependenciesService.TargetVersion._
    atVersion match {
      case Latest => slugInfoService.getSlugInfo(name, SlugInfoFlag.Latest)
      case Labelled(version) => slugInfoService.getSlugInfo(name, version.toString)
    }
  }

  private def toCuratedDependencies(optSlugInfo: Option[SlugInfo]): Option[List[SlugDependency]] =
    optSlugInfo.map(curatedDependenciesOf)

  private def curatedDependenciesOf(slugInfo: SlugInfo): List[SlugDependency] =
    slugInfo.dependencies.filter(slugDependency => curatedLibraries.contains(slugDependency.artifact))

  private type VersionLookup = String => Option[Version]

  private def enrichSlugDependencies(versionLookup: VersionLookup,
                                     slugDependencies: Option[List[SlugDependency]]): Future[Option[List[Dependency]]] = {
    val asDependencyWithLatestVersion = toDependency(versionLookup) _
    slugDependencies.fold[Future[Option[List[Dependency]]]](ifEmpty = Future.successful(None)) { slugDependencies =>
      val dependencies = slugDependencies.map(asDependencyWithLatestVersion)
      serviceConfigsService.getDependenciesWithBobbyRules(dependencies).map(Some(_))
    }
  }

  private def toDependency(latestVersionLookup: VersionLookup)(slugDependency: SlugDependency): Dependency =
    Dependency(
      name = slugDependency.artifact,
      currentVersion = Version(slugDependency.version),  // TODO this is unsafe
      latestVersion = latestVersionLookup(slugDependency.artifact),
      bobbyRuleViolations = Nil
    )
}

object SlugDependenciesService {
  sealed trait TargetVersion

  object TargetVersion {
    case object Latest extends TargetVersion
    case class Labelled(version: Version) extends TargetVersion
  }
}