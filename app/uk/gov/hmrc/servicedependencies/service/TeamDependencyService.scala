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
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicedependencies.model.RepoType

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
      repos          <- teamsAndReposConnector.getAllRepositories(archived = Some(false), teamName = Some(teamName))
      results        <- repos.foldLeftM(Seq.empty[Dependencies]):
                          case (acc, repo) if repo.repoType == RepoType.Library || repo.repoType == RepoType.Test =>
                            metaArtefactRepository.find(repo.name).map:
                              case None       => acc
                              case Some(meta) => acc :+ toDependencies(curatedLibrariesService.toRepository(meta, latestVersions, bobbyRules, Nil))
                          case (acc, repo) if repo.repoType == RepoType.Service =>
                            metaArtefactRepository.find(repo.name).map:
                              case None       => acc :+ Dependencies(repositoryName = repo.name, libraryDependencies = Nil, sbtPluginsDependencies = Nil, otherDependencies = Nil)
                              case Some(meta) => acc :+ toDependencies(curatedLibrariesService.toRepository(meta, latestVersions, bobbyRules, Nil))
                          case (acc, repo)  =>
                            Future.successful(acc)
    yield results

  def teamServiceDependenciesMap(
    teamName: String
  , flag    : SlugInfoFlag
  )(using hc: HeaderCarrier
  ): Future[Map[String, Seq[Dependency]]] =
    for
      bobbyRules     <- serviceConfigsConnector.getBobbyRules()
      latestVersions <- latestVersionRepository.getAllEntries()
      repos          <- teamsAndReposConnector.getAllRepositories(archived = Some(false), teamName = Some(teamName), repoType = Some(RepoType.Service))
      res            <- repos.foldLeftM(Seq.empty[(String, Seq[Dependency])]): (acc, repo) =>
                          for
                            optMeta <- OptionT(slugInfoRepository.getSlugInfo(repo.name, flag))
                                         .flatMap(slugInfo => OptionT(metaArtefactRepository.find(repo.name, slugInfo.version)))
                                         .value
                            optDeps =  optMeta.map: meta =>
                                         val x = toDependencies(curatedLibrariesService.toRepository(meta, latestVersions, bobbyRules, Nil))
                                         x.sbtPluginsDependencies ++ x.libraryDependencies
                          yield acc :+ (repo.name, optDeps.getOrElse(Nil))
    yield res.toMap

  private def toDependencies(curatedRepo:CuratedLibrariesService.Repository): Dependencies =
    Dependencies(
      repositoryName         = curatedRepo.name
    , libraryDependencies    = curatedRepo.modules.flatMap(m => m.dependenciesCompile ) ++
                               curatedRepo.modules.flatMap(m => m.dependenciesProvided) ++
                               curatedRepo.modules.flatMap(m => m.dependenciesTest    ) ++
                               curatedRepo.modules.flatMap(m => m.dependenciesIt      )
    , sbtPluginsDependencies = curatedRepo.dependenciesBuild
    , otherDependencies      = Nil
    )
