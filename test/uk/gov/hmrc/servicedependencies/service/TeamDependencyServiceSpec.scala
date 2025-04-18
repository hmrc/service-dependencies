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

import org.mockito.Mockito.when
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{DependencyConfig, CuratedDependencyConfig}
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.Dependency
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, DependencyScope, RepoType, Version}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository, SlugInfoRepository, TestSlugInfos}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TeamDependencyServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  val mockTeamsAndReposConnector    = mock[TeamsAndRepositoriesConnector]
  val mockSlugInfoRepository        = mock[SlugInfoRepository]
  val mockServiceConfigsConnector   = mock[ServiceConfigsConnector]
  val mockLatestVersionRepository   = mock[LatestVersionRepository]
  val mockMetaArtefactRepository    = mock[MetaArtefactRepository]
  val mockServiceDependenciesConfig = mock[ServiceDependenciesConfig]

  val tds = TeamDependencyService(
      mockTeamsAndReposConnector
    , mockSlugInfoRepository
    , mockServiceConfigsConnector
    , CuratedLibrariesService(mockServiceDependenciesConfig)
    , mockLatestVersionRepository
    , mockMetaArtefactRepository
    )

  "findAllDepsForTeam" should {
    "return dependencies for all projects belonging to team" in {
      given HeaderCarrier = HeaderCarrier()

      when(mockTeamsAndReposConnector.getAllRepositories(archived = Some(false), teamName = Some("foo")))
        .thenReturn(Future.successful(Seq(TeamsAndRepositoriesConnector.Repository(
          name           = "my-slug"
        , teamNames      = Seq("foo")
        , digitalService = None
        , repoType       = RepoType.Service
        , isArchived     = false
        ))))

      when(mockServiceConfigsConnector.getBobbyRules())
        .thenReturn(Future.successful(BobbyRules(Map.empty)))

      when(mockLatestVersionRepository.getAllEntries())
        .thenReturn(Future.successful(Seq()))

      val metaArtefact = TestSlugInfos.metaArtefact.copy(modules = TestSlugInfos.metaArtefact.modules.map(_.copy(dependencyDotCompile = Some(scala.io.Source.fromResource("graphs/dependencies-compile.dot").mkString))))

      when(mockMetaArtefactRepository.find("my-slug"))
        .thenReturn(Future.successful(Some(metaArtefact)))

      when(mockServiceDependenciesConfig.curatedDependencyConfig)
        .thenReturn(CuratedDependencyConfig(
          sbtPlugins = List.empty
        , libraries  = List(DependencyConfig(group = "org.typelevel", name = "cats-core", latestVersion = None))
        , others     = List.empty
        ))

      val res = tds.findAllDepsForTeam("foo").futureValue

      res.head.libraryDependencies should contain (Dependency("cats-core", "org.typelevel", Some("2.12"), Version("2.2.0"), None, List(), Seq.empty, None, DependencyScope.Compile))
    }
  }
}
