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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.Dependency
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, DependencyScope, SlugInfoFlag, Team, Version}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository, SlugInfoRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TeamDependencyServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience
     with ArgumentMatchersSugar {
  val mockTeamsAndReposConnector  = mock[TeamsAndRepositoriesConnector]
  val mockSlugInfoRepository      = mock[SlugInfoRepository]
  val mockServiceConfigsConnector = mock[ServiceConfigsConnector]
  val mockSlugDependenciesService = mock[SlugDependenciesService]
  val mockLatestVersionRepository = mock[LatestVersionRepository]
  val mockMetaArtefactRepository  = mock[MetaArtefactRepository]

  val tds = new TeamDependencyService(
      mockTeamsAndReposConnector
    , mockSlugInfoRepository
    , mockServiceConfigsConnector
    , mockSlugDependenciesService
    , mockLatestVersionRepository
    , mockMetaArtefactRepository
    )

  "findAllDepsForTeam" should {
    "return dependencies for all projects belonging to team" in {
      implicit val hc: HeaderCarrier = new HeaderCarrier()
      val team = new Team("foo", Map("Service" -> Seq("foo-service")))

      val fooDep1 =
        Dependency(
          name                = "foo-dep1",
          group               = "uk.gov.hmrc",
          currentVersion      = Version("1.2.0"),
          latestVersion       = None,
          bobbyRuleViolations = List.empty,
          scope               = Some(DependencyScope.Compile)
        )
      val fooSlugDep =
        Dependency(
          name                = "foo-dep2",
          group               = "uk.gov.hmrc",
          currentVersion      = Version("7.7.7"),
          latestVersion       = None,
          bobbyRuleViolations = List.empty,
          scope               = Some(DependencyScope.Compile)
        )

      when(mockTeamsAndReposConnector.getTeam("foo"))
        .thenReturn(Future.successful(team))

      when(mockServiceConfigsConnector.getBobbyRules)
        .thenReturn(Future.successful(BobbyRules(Map.empty)))

      when(mockLatestVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Seq()))

      when(mockMetaArtefactRepository.find("foo-service"))
        .thenReturn(Future.successful(None))

      when(mockSlugDependenciesService.curatedLibrariesOfSlug("foo-service", SlugInfoFlag.Latest, BobbyRules(Map.empty), Seq.empty))
        .thenReturn(Future.successful(Option(List(fooDep1, fooSlugDep))))

      val res = tds.findAllDepsForTeam("foo").futureValue

      res.head.libraryDependencies should contain (fooDep1)
      res.head.libraryDependencies should contain (fooSlugDep)
    }
  }
}
