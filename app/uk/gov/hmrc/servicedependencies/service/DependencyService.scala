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

// class DependencyService @Inject()(
//   metaArtefactRepository              : MetaArtefactRepository,
//   derivedDependencyRepository         : DerivedDependencyRepository,
//   derivedServiceDependenciesRepository: DerivedServiceDependenciesRepository,
//   teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
// )(implicit ec: ExecutionContext) {

//   def addDependencies(metaArtefact: MetaArtefact)(implicit hc: HeaderCarrier): Future[Unit] =
//     for {
//       oRepo    <- teamsAndRepositoriesConnector.getRepository(metaArtefact.name)
//       repoType =  oRepo.fold(RepoType.Other: RepoType)(_.repoType)
//       isLatest <- metaArtefactRepository
//                     .find(metaArtefact.name)
//                     .map {
//                       case Some(storedMeta) => metaArtefact.version >= storedMeta.version
//                       case None             => true
//                     }
//       deps     =  DependencyService
//                     .parseMetaArtefact(metaArtefact)
//                     .map { case (node, scopes) => MetaArtefactDependency.apply(metaArtefact, repoType, node, scopes) } // TODO move into parseMetaArtefact ??
//                     .toSeq
//       _        <- if      (isLatest)                     derivedDependencyRepository.put(deps)
//                   else if (repoType == RepoType.Service) derivedServiceDependenciesRepository.put(deps)
//                   else                                   Future.unit
//     } yield ()

//   def deleteDependencies(name: String, version: Option[Version] = None): Future[Unit] =
//     for {
//       _ <- derivedDependencyRepository.delete(name, version)
//       _ <- derivedServiceDependenciesRepository.delete(name, version)
//     } yield ()
// }

object DependencyService {

  private def parseStr(opt: Option[String], scope: DependencyScope): Seq[(DependencyGraphParser.Node, DependencyScope)] =
    DependencyGraphParser.parse(opt.getOrElse("")).dependencies.map((_, scope))

  def parseMetaArtefact(meta: MetaArtefact): Map[DependencyGraphParser.Node, Set[DependencyScope]] = {
    val build   = parseStr(meta.dependencyDotBuild, DependencyScope.Build)
    val modules = meta.modules.foldLeft(Seq.empty[(DependencyGraphParser.Node, DependencyScope)]) { (acc, module) =>
      acc                                                              ++
      parseStr(module.dependencyDotCompile , DependencyScope.Compile ) ++
      parseStr(module.dependencyDotProvided, DependencyScope.Provided) ++
      parseStr(module.dependencyDotTest    , DependencyScope.Test    ) ++
      parseStr(module.dependencyDotIt      , DependencyScope.It      )
    }

    val scala = meta
                  .modules
                  .flatMap(_.crossScalaVersions.toSeq.flatten)
                  .headOption
                  .map(v => DependencyGraphParser.Node.apply(s"org.scala-lang:scala-library:${v.original}"))
                  .fold(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]])(n => Map(n ->  Set(DependencyScope.Compile, DependencyScope.Test)))
    val sbt   = meta
                  .modules
                  .flatMap(_.sbtVersion)
                  .headOption
                  .map(v => DependencyGraphParser.Node.apply(s"org.scala-sbt:sbt:${v.original}"))
                  .fold(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]])(n => Map(n ->  Set(DependencyScope.Build)))
    val deps  = (build ++ modules)
                  .foldLeft(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]]) { case (acc, (n, flag)) => acc + (n -> (acc.getOrElse(n, Set.empty) + flag)) }
                  .filterNot { case (node, _) => node.group == "default"     && node.artefact == "project" }
                  .filterNot { case (node, _) => node.group == "uk.gov.hmrc" && node.artefact == meta.name }

    scala ++ sbt ++ deps
  }
}
