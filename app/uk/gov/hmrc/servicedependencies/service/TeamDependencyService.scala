/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.data.OptionT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, DependencyScope, MetaArtefact, LatestVersion, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamDependencyService @Inject()(
  teamsAndReposConnector       : TeamsAndRepositoriesConnector
, slugInfoRepository           : SlugInfoRepository
, serviceConfigsConnector      : ServiceConfigsConnector
, slugDependenciesService      : SlugDependenciesService
, latestVersionRepository      : LatestVersionRepository
, metaArtefactRepository       : MetaArtefactRepository
)(implicit ec: ExecutionContext
) {

  def findAllDepsForTeam(teamName: String)(implicit hc: HeaderCarrier): Future[Seq[Dependencies]] =
    for {
      bobbyRules     <- serviceConfigsConnector.getBobbyRules
      latestVersions <- latestVersionRepository.getAllEntries
      team           <- teamsAndReposConnector.getTeam(teamName)
      libs           <- team.libraries.toList.traverse { repoName =>
                          metaArtefactRepository.find(repoName)
                            .map(_.map(dependenciesFromMetaArtefact(_, bobbyRules, latestVersions)))
                        }.map(_.flatten)
      services       <- team.services.toList.traverse { repoName =>
                            metaArtefactRepository.find(repoName).flatMap {
                            case Some(metaArtefact) => Future.successful(Some(dependenciesFromMetaArtefact(metaArtefact, bobbyRules, latestVersions)))
                            case None               => slugDependenciesService.curatedLibrariesOfSlug(
                                                         repoName,
                                                         SlugInfoFlag.Latest,
                                                         bobbyRules,
                                                         latestVersions
                                                       ).map(_.map(dependencies =>
                                                           Dependencies(
                                                             repositoryName         = repoName,
                                                             libraryDependencies    = dependencies.filter(d => d.scope == Some(DependencyScope.Compile) || d.scope == Some(DependencyScope.Test)),
                                                             sbtPluginsDependencies = dependencies.filter(_.scope == Some(DependencyScope.Build)),
                                                             otherDependencies      = Seq.empty
                                                           )
                                                       ))
                          }
                        }.map(_.flatten)
    } yield libs ++ services

  private def dependenciesFromMetaArtefact(
    metaArtefact  : MetaArtefact,
    bobbyRules    : BobbyRules,
    latestVersions: Seq[LatestVersion]
  ): Dependencies = {
    def toDependencies(name: String, scope: DependencyScope, dotFile: String) =
      slugDependenciesService.curatedLibrariesFromGraph(
        dotFile        = dotFile,
        rootName       = name,
        latestVersions = latestVersions,
        bobbyRules     = bobbyRules,
        scope          = scope
      )
    Dependencies(
      repositoryName         = metaArtefact.name,
      libraryDependencies    = metaArtefact.modules.flatMap(m => m.dependencyDotCompile.fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Compile, s))) ++
                               metaArtefact.modules.flatMap(m => m.dependencyDotTest   .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Test   , s))) ++
                               metaArtefact.modules.flatMap(m => m.dependencyDotIt     .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.It     , s))),
      sbtPluginsDependencies = metaArtefact.dependencyDotBuild.fold(Seq.empty[Dependency])(s => toDependencies(metaArtefact.name, DependencyScope.Build, s)),
      otherDependencies      = Seq.empty
    )
  }

  def dependenciesOfSlugsForTeam(
    teamName: String
  , flag    : SlugInfoFlag
  )(implicit hc: HeaderCarrier
  ): Future[Map[String, Seq[Dependency]]] =
    for {
      team           <- teamsAndReposConnector.getTeam(teamName)
      latestVersions <- latestVersionRepository.getAllEntries
      bobbyRules     <- serviceConfigsConnector.getBobbyRules
      res            <- team.services.toList.traverse { serviceName =>
                          for {
                            optMetaArtefact <- OptionT(slugInfoRepository.getSlugInfo(serviceName, flag))
                                                 .flatMap(slugInfo => OptionT(metaArtefactRepository.find(serviceName, slugInfo.version)))
                                                 .value
                            optDeps         <- optMetaArtefact match {
                                                 case Some(metaArtefact) =>
                                                   def toDependencies(name: String, scope: DependencyScope, dotFile: String) =
                                                     slugDependenciesService.curatedLibrariesFromGraph(
                                                       dotFile        = dotFile,
                                                       rootName       = name,
                                                       latestVersions = latestVersions,
                                                       bobbyRules     = bobbyRules,
                                                       scope          = scope
                                                     )

                                                   Future.successful(
                                                     Some(
                                                       metaArtefact.dependencyDotBuild.fold(Seq.empty[Dependency])(s => toDependencies(metaArtefact.name, DependencyScope.Build, s)) ++
                                                         metaArtefact.modules.flatMap(m => m.dependencyDotCompile.fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Compile, s))) ++
                                                         metaArtefact.modules.flatMap(m => m.dependencyDotTest   .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Test   , s))) ++
                                                         metaArtefact.modules.flatMap(m => m.dependencyDotIt     .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.It     , s)))
                                                     )
                                                   )

                                                 case None => slugDependenciesService.curatedLibrariesOfSlug(serviceName, flag, bobbyRules, latestVersions)
                                               }
                          } yield optDeps.map(serviceName -> _)
                        }
    } yield res.collect { case Some(kv) => kv }.toMap
}
