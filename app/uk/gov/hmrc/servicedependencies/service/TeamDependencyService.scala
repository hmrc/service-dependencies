/*
 * Copyright 2023 HM Revenue & Customs
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
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, DependencyScope, LatestVersion, MetaArtefact, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamDependencyService @Inject()(
  teamsAndReposConnector : TeamsAndRepositoriesConnector
, slugInfoRepository     : SlugInfoRepository
, serviceConfigsConnector: ServiceConfigsConnector
, curatedLibrariesService: CuratedLibrariesService
, latestVersionRepository: LatestVersionRepository
, metaArtefactRepository : MetaArtefactRepository
)(using ec: ExecutionContext
):

  def findAllDepsForTeam(teamName: String)(using hc: HeaderCarrier): Future[Seq[Dependencies]] =
    for
      bobbyRules     <- serviceConfigsConnector.getBobbyRules()
      latestVersions <- latestVersionRepository.getAllEntries()
      team           <- teamsAndReposConnector.getTeam(teamName)
      libsAndTests   <- (team.libraries ++ team.tests).toList
                          .traverse: repoName =>
                            metaArtefactRepository.find(repoName)
                              .map(_.map(dependenciesFromMetaArtefact(_, bobbyRules, latestVersions)))
                          .map(_.flatten)
      services       <- team.services.toList.traverse: repoName =>
                            metaArtefactRepository.find(repoName).map:
                              case None               => Dependencies(repositoryName = repoName, libraryDependencies = Nil, sbtPluginsDependencies = Nil, otherDependencies = Nil)
                              case Some(metaArtefact) => dependenciesFromMetaArtefact(metaArtefact, bobbyRules, latestVersions)
    yield libsAndTests ++ services

  def teamServiceDependenciesMap(
    teamName: String
  , flag    : SlugInfoFlag
  )(using hc: HeaderCarrier
  ): Future[Map[String, Seq[Dependency]]] =
    for
      bobbyRules     <- serviceConfigsConnector.getBobbyRules()
      latestVersions <- latestVersionRepository.getAllEntries()
      team           <- teamsAndReposConnector.getTeam(teamName)
      res            <- team.services.toList.traverse: serviceName =>
                          for
                            optMetaArtefact <- OptionT(slugInfoRepository.getSlugInfo(serviceName, flag))
                                                 .flatMap(slugInfo => OptionT(metaArtefactRepository.find(serviceName, slugInfo.version)))
                                                 .value
                            optDeps         =  optMetaArtefact.map { metaArtefact =>
                                                 val x = dependenciesFromMetaArtefact(metaArtefact, bobbyRules, latestVersions)
                                                 x.sbtPluginsDependencies ++ x.libraryDependencies
                                               }
                          yield optDeps.map(serviceName -> _)
    yield res.collect { case Some(kv) => kv }.toMap

  private def dependenciesFromMetaArtefact(
    metaArtefact  : MetaArtefact,
    bobbyRules    : BobbyRules,
    latestVersions: Seq[LatestVersion]
  ): Dependencies =
    def toDependencies(name: String, scope: DependencyScope, dotFile: String) =
      curatedLibrariesService.fromGraph(
        dotFile        = dotFile,
        rootName       = name,
        latestVersions = latestVersions,
        bobbyRules     = bobbyRules,
        scope          = scope,
        subModuleNames = metaArtefact.subModuleNames
      )
    Dependencies(
      repositoryName         = metaArtefact.name,
      libraryDependencies    = metaArtefact.modules.flatMap(m => m.dependencyDotCompile .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Compile , s))) ++
                               metaArtefact.modules.flatMap(m => m.dependencyDotProvided.fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Provided, s))) ++
                               metaArtefact.modules.flatMap(m => m.dependencyDotTest    .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Test    , s))) ++
                               metaArtefact.modules.flatMap(m => m.dependencyDotIt      .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.It      , s))),
      sbtPluginsDependencies = metaArtefact.dependencyDotBuild.fold(Seq.empty[Dependency])(s => toDependencies(metaArtefact.name, DependencyScope.Build, s)),
      otherDependencies      = Seq.empty
    )
