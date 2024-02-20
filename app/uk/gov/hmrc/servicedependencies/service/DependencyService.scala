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
import uk.gov.hmrc.servicedependencies.model.RepoType.{All, Other}
import uk.gov.hmrc.servicedependencies.model.{BobbyVersionRange, DependencyScope, MetaArtefact, MetaArtefactDependency, RepoType}
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedDependencyRepository
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}

class DependencyService @Inject()(
                                   metaArtefactRepository: MetaArtefactRepository,
                                   derivedDependencyRepository: DerivedDependencyRepository,
                                   teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
                                 )(implicit ec: ExecutionContext, hc: HeaderCarrier) {

  private def artefactVersionControl(metaArtefact: MetaArtefact): Future[Option[MetaArtefact]] = {
    metaArtefactRepository.find(metaArtefact.name).map {
      case Some(value) =>
        if (metaArtefact.version.compare(value.version) > 0) {
          Some(metaArtefact)
        } else {
          None
        }
      case None => Some(metaArtefact)
    }
  }
  def setArtefactDependencies(metaArtefact: MetaArtefact): Future[Unit] = {
    artefactVersionControl(metaArtefact).flatMap {
      case Some(value) =>
        teamsAndRepositoriesConnector.getRepository(value.name).flatMap {
          repo =>
            derivedDependencyRepository.addAndReplace(MetaArtefactDependency.fromMetaArtefact(value, repo.map(_.repoType).getOrElse(Other)))
        }
      case None => Future.successful(())
    }
  }
}

object DependencyService {

  def parseArtefactDependencies(meta: MetaArtefact): Map[DependencyGraphParser.Node, Set[DependencyScope]] = {

    val optMetaModule = meta.modules.find(_.name == meta.name).orElse(meta.modules.headOption)

    val graphBuild    = meta.dependencyDotBuild.getOrElse("")
    val graphCompile  = optMetaModule.flatMap(_.dependencyDotCompile).getOrElse("")
    val graphProvided = optMetaModule.flatMap(_.dependencyDotProvided).getOrElse("")
    val graphTest     = optMetaModule.flatMap(_.dependencyDotTest).getOrElse("")
    val graphIt       = optMetaModule.flatMap(_.dependencyDotIt).getOrElse("")

    val build     = DependencyGraphParser.parse(graphBuild).dependencies.map((_, DependencyScope.Build))
    val compile   = DependencyGraphParser.parse(graphCompile).dependencies.map((_, DependencyScope.Compile))
    val provided  = DependencyGraphParser.parse(graphProvided).dependencies.map((_, DependencyScope.Provided))
    val test      = DependencyGraphParser.parse(graphTest).dependencies.map((_, DependencyScope.Test))
    val it        = DependencyGraphParser.parse(graphIt).dependencies.map((_, DependencyScope.It))

    (build ++ compile ++ provided ++ test ++ it)
      .foldLeft(Map.empty[DependencyGraphParser.Node, Set[DependencyScope]]) { case (acc, (n, flag)) =>
        acc + (n -> (acc.getOrElse(n, Set.empty) + flag))
      }
  }
}
