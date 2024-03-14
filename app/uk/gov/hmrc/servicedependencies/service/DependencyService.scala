/*
 * Copyright 2024 HM Revenue & Customs
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

import com.google.inject.Inject
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.model.RepoType
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, MetaArtefact, MetaArtefactDependency, Version}
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDependencyRepository, DerivedServiceDependenciesRepository}
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}

class DependencyService @Inject()(
  metaArtefactRepository              : MetaArtefactRepository,
  derivedDependencyRepository         : DerivedDependencyRepository,
  derivedServiceDependenciesRepository: DerivedServiceDependenciesRepository,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
)(implicit ec: ExecutionContext) {

  def addDependencies(metaArtefact: MetaArtefact)(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      oRepo    <- teamsAndRepositoriesConnector.getRepository(metaArtefact.name)
      repoType =  oRepo.fold(RepoType.Other: RepoType)(_.repoType)
      isLatest <- metaArtefactRepository
                    .find(metaArtefact.name)
                    .map {
                      case Some(storedMeta) => metaArtefact.version >= storedMeta.version
                      case None             => true
                    }
      _        <- if (isLatest) derivedDependencyRepository.put(MetaArtefactDependency.fromMetaArtefact(metaArtefact, repoType))
                  else          Future.unit
      _        <- if (repoType == RepoType.Service) derivedServiceDependenciesRepository.populateDependencies(metaArtefact)
                  else                              Future.unit
    } yield ()

  def deleteDependencies(name: String, version: Version): Future[Unit] =
    for {
      _ <- derivedDependencyRepository.delete(name, version)
      _ <- derivedServiceDependenciesRepository.delete(name, version)
    } yield ()
}

object DependencyService {

  def parseArtefactDependencies(meta: MetaArtefact): Map[DependencyGraphParser.Node, Set[DependencyScope]] = {

    val graphBuild = meta.dependencyDotBuild.getOrElse("")
    val build = DependencyGraphParser.parse(graphBuild).dependencies.map((_, DependencyScope.Build))

    (
      meta.modules.foldLeft(Seq.empty[(DependencyGraphParser.Node, DependencyScope)]) {
      (acc, module) =>

        val graphCompile  = module.dependencyDotCompile.getOrElse("")
        val graphProvided = module.dependencyDotProvided.getOrElse("")
        val graphTest     = module.dependencyDotTest.getOrElse("")
        val graphIt       = module.dependencyDotIt.getOrElse("")

        val compile   = DependencyGraphParser.parse(graphCompile).dependencies.map((_, DependencyScope.Compile))
        val provided  = DependencyGraphParser.parse(graphProvided).dependencies.map((_, DependencyScope.Provided))
        val test      = DependencyGraphParser.parse(graphTest).dependencies.map((_, DependencyScope.Test))
        val it        = DependencyGraphParser.parse(graphIt).dependencies.map((_, DependencyScope.It))

        acc ++ (compile ++ provided ++ test ++ it)
      } ++ build
    ).foldLeft(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]]) { case (acc, (n, flag)) =>
      acc + (n -> (acc.getOrElse(n, Set.empty) + flag))
    }
  }
}
