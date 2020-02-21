/*
 * Copyright 2020 HM Revenue & Customs
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

import java.time.Instant

import org.mockito.{ArgumentMatchers, ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, SlugInfoFlag, Team, Version}
import uk.gov.hmrc.servicedependencies.persistence.SlugInfoRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TeamDependencyServiceSpec extends AnyWordSpec with Matchers with MockitoSugar with ScalaFutures with ArgumentMatchersSugar {
  val mockTeamsAndReposConnector        = mock[TeamsAndRepositoriesConnector]
  val mockSlugInfoRepository            = mock[SlugInfoRepository]
  val mockRepositoryDependenciesService = mock[RepositoryDependenciesService]
  val mockServiceConfigsConnector       = mock[ServiceConfigsConnector]
  val mockSlugDependenciesService       = mock[SlugDependenciesService]
  val tds = new TeamDependencyService(
      mockTeamsAndReposConnector
    , mockSlugInfoRepository
    , mockRepositoryDependenciesService
    , mockServiceConfigsConnector
    , mockSlugDependenciesService
    )

  "replaceServiceDeps" should {
    "replace library section with slug data" in {

      val lib1 = new Dependency(name = "foolib", group = "uk.gov.hmrc", currentVersion = Version("1.2.3"), latestVersion = None, bobbyRuleViolations = List.empty)
      val lib2 = new Dependency(name = "foolib", group = "uk.gov.hmrc", currentVersion = Version("1.2.4"), latestVersion = None, bobbyRuleViolations = List.empty)
      val dep = Dependencies("foo", libraryDependencies = Seq(lib1), Nil, Nil, Instant.now() )

      when(mockSlugDependenciesService.curatedLibrariesOfSlug(dep.repositoryName, SlugInfoFlag.Latest))
        .thenReturn(Future.successful(Option(List(lib2))))

      val res = tds.replaceServiceDependencies(dep).futureValue

      res.libraryDependencies shouldBe Seq(lib2)
      res.sbtPluginsDependencies shouldBe Nil
      res.otherDependencies shouldBe Nil
    }
  }

  "findAllDepsForTeam" should {

    "return dependencies for all projects belonging to  team" in {
      implicit val hc: HeaderCarrier = new HeaderCarrier()
      val team = new Team("foo", Map("Service" -> Seq("foo-service")))

      when(mockTeamsAndReposConnector.getTeamDetails("foo"))
        .thenReturn(Future.successful(team))

      val fooDep1    = Dependency(name = "foo-dep1", group = "uk.gov.hmrc", currentVersion = Version("1.2.0"), latestVersion = None, bobbyRuleViolations = List.empty)
      val fooDep2    = Dependency(name = "foo-dep2", group = "uk.gov.hmrc", currentVersion = Version("0.6.0"), latestVersion = None, bobbyRuleViolations = List.empty)
      val fooSlugDep = Dependency(name = "foo-dep2", group = "uk.gov.hmrc", currentVersion = Version("7.7.7"), latestVersion = None, bobbyRuleViolations = List.empty)

      val fooDependencies = Dependencies(
          "foo-service"
        , libraryDependencies    = Seq(fooDep1, fooDep2)
        , sbtPluginsDependencies = Seq.empty
        , otherDependencies      = Seq.empty
        , lastUpdated            = Instant.now()
        )

      when(mockRepositoryDependenciesService.getDependencyVersionsForAllRepositories)
        .thenReturn(Future.successful(Seq(fooDependencies)))

      when(mockSlugDependenciesService.curatedLibrariesOfSlug("foo-service", SlugInfoFlag.Latest))
        .thenReturn(Future.successful(Option(List(fooDep1, fooSlugDep))))

      when(mockServiceConfigsConnector.getBobbyRules)
        .thenReturn(Future.successful(BobbyRules(Map.empty)))

      val res = tds.findAllDepsForTeam("foo").futureValue

      res.head.libraryDependencies should contain (fooDep1)
      res.head.libraryDependencies should contain (fooSlugDep)
    }
  }
}
