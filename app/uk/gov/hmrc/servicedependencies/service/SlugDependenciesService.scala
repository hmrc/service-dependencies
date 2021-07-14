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

package uk.gov.hmrc.servicedependencies.service

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, ImportedBy}
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, MongoLatestVersion, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.LatestVersionRepository

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class SlugDependenciesService @Inject()(
  slugInfoService          : SlugInfoService
, serviceDependenciesConfig: ServiceDependenciesConfig
, latestVersionRepository  : LatestVersionRepository
, serviceConfigsConnector  : ServiceConfigsConnector
, graphParser              : DependencyGraphParser
)(implicit ec: ExecutionContext
) {

  private lazy val curatedDependencyConfig: CuratedDependencyConfig =
    serviceDependenciesConfig.curatedDependencyConfig

  /*
   * We may want to evolve the model - but for this initial version we reuse the existing Dependency definition.
   */
  def curatedLibrariesOfSlug(name: String, flag: SlugInfoFlag): Future[Option[List[Dependency]]] =
    for {
      latestVersions   <- latestVersionRepository.getAllEntries
      curatedLibraries <- curatedLibrariesOfSlug(name, flag, latestVersions)
    } yield curatedLibraries

  def curatedLibrariesOfSlug(name: String, flag: SlugInfoFlag, latestVersions: Seq[MongoLatestVersion]): Future[Option[List[Dependency]]] =
    slugInfoService.getSlugInfo(name, flag).flatMap {
      case None                                                   => Future.successful(None)
      case Some(slugInfo) if(slugInfo.dependencyDotCompile == "") => curatedLibrariesOfSlugInfo(slugInfo, latestVersions).map(Some.apply)
      case Some(slugInfo)                                         => curatedLibrariesOfSlugInfoFromGraph(slugInfo, latestVersions).map(Some.apply)
    }

  private def curatedLibrariesOfSlugInfo(slugInfo: SlugInfo, latestVersions: Seq[MongoLatestVersion]): Future[List[Dependency]] =
    for {
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


  private def curatedLibrariesOfSlugInfoFromGraph(slugInfo: SlugInfo, latestVersions: Seq[MongoLatestVersion], scope: DependencyScope = DependencyScope.Compile): Future[List[Dependency]] =
    for {
      bobbyRules     <- serviceConfigsConnector.getBobbyRules
      graph          =  graphParser.parse(dotFileForScope(slugInfo, scope))
      dependencies   =  graph
        .dependencies
        .filterNot(_.artefact == slugInfo.name)
        .flatMap { graphDependency =>
          val latestVersion =
            latestVersions
              .find(v => v.group == graphDependency.group && v.name == graphDependency.artefact)
              .map(_.version)
          Version.parse(graphDependency.version).map { currentVersion =>
            Dependency(
                name                = graphDependency.artefact  // graphDependency.name is the full unparsed string, not the artifact's name
              , group               = graphDependency.group
              , currentVersion      = currentVersion
              , latestVersion       = latestVersion
              , bobbyRuleViolations = bobbyRules.violationsFor(
                  group   = graphDependency.group
                , name    = graphDependency.artefact
                , version = currentVersion
              )
              , importBy = graph.pathToRoot(graphDependency)
                .dropRight(1)
                .lastOption.map(n => ImportedBy(n.artefact, n.group, Version(n.version)))
                .filterNot(d => d.name == graphDependency.artefact
                             && d.group == graphDependency.group)
            )
          }
        }
      filtered       = dependencies.filter(dependency =>
        // filter for direct dependencies in the curatedDependency list or any dependency with a bobby violation
        (dependency.importBy.isEmpty && curatedDependencyConfig.allDependencies.exists(lib => lib.name == dependency.name && lib.group == dependency.group))
          || dependency.bobbyRuleViolations.nonEmpty
      ).toList
    } yield filtered

  private def dotFileForScope(slugInfo: SlugInfo, scope: DependencyScope) : String =
    scope match {
      case DependencyScope.Compile => slugInfo.dependencyDotCompile
      case DependencyScope.Test    => slugInfo.dependencyDotTest
      case DependencyScope.Build   => slugInfo.dependencyDotBuild
    }
}
