/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.controller

import java.time.{Instant, LocalDate}

import org.mockito.ArgumentMatchers.any
import org.mockito.{Mockito, MockitoSugar}
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.Configuration
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency, DependencyBobbyRule}
import uk.gov.hmrc.servicedependencies.model.{BobbyVersionRange, Version}
import uk.gov.hmrc.servicedependencies.service.SlugDependenciesService.TargetVersion.{Labelled, Latest}
import uk.gov.hmrc.servicedependencies.service._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ServiceDependenciesControllerSpec
    extends AnyFreeSpec
    with BeforeAndAfterEach
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with IntegrationPatience
    with OptionValues {

  "getDependencyVersionsForRepository" - {
    "should get dependency versions for a repository using the service" in {
      val boot                   = Boot.init
      val repoName               = "repo1"
      val now                    = Instant.now()
      val repositoryDependencies = Dependencies(repoName, Seq(), Seq(), Seq(), now)

      when(boot.mockedDependencyDataUpdatingService.getDependencyVersionsForRepository(any()))
        .thenReturn(Future.successful(Some(repositoryDependencies)))

      when(boot.mockServiceConfigsService.getDependenciesWithBobbyRules(repositoryDependencies))
        .thenReturn(Future.successful(repositoryDependencies))

      val result = boot.controller
        .getDependencyVersionsForRepository(repoName)
        .apply(FakeRequest())

      contentAsJson(result).toString shouldBe
        s"""{"repositoryName":"repo1","libraryDependencies":[],"sbtPluginsDependencies":[],"otherDependencies":[],"lastUpdated":"$now"}"""
      Mockito.verify(boot.mockedDependencyDataUpdatingService).getDependencyVersionsForRepository(repoName)
    }
  }

  "get dependencies" - {
    "should get all dependencies using the service" in {
      val boot                = Boot.init
      val now                 = Instant.now()
      val repo1               = Dependencies("repo1", Seq(), Seq(), Seq(), now)
      val repo2               = Dependencies("repo2", Seq(), Seq(), Seq(), now)
      val repo3               = Dependencies("repo3", Seq(), Seq(), Seq(), now)
      val libraryDependencies = Seq(repo1, repo2, repo3)

      when(boot.mockedDependencyDataUpdatingService.getDependencyVersionsForAllRepositories())
        .thenReturn(Future.successful(libraryDependencies))

      when(boot.mockServiceConfigsService.getDependenciesWithBobbyRules(repo1))
        .thenReturn(Future.successful(repo1))

      when(boot.mockServiceConfigsService.getDependenciesWithBobbyRules(repo2))
        .thenReturn(Future.successful(repo2))

      when(boot.mockServiceConfigsService.getDependenciesWithBobbyRules(repo3))
        .thenReturn(Future.successful(repo3))

      val result = boot.controller.dependencies().apply(FakeRequest())

      contentAsJson(result).toString shouldBe
        s"""[{"repositoryName":"repo1","libraryDependencies":[],"sbtPluginsDependencies":[],"otherDependencies":[],"lastUpdated":"$now"},""" +
          s"""{"repositoryName":"repo2","libraryDependencies":[],"sbtPluginsDependencies":[],"otherDependencies":[],"lastUpdated":"$now"},""" +
          s"""{"repositoryName":"repo3","libraryDependencies":[],"sbtPluginsDependencies":[],"otherDependencies":[],"lastUpdated":"$now"}]"""
    }
  }

  "getDependenciesOfSlug" - {

    "should get dependencies of a known slug version using the service when a valid version is supplied" in new GetDependenciesOfSlugFixture {
      val SlugVersion = Version(major = 1, minor = 2, patch = 3)
      when(boot.mockedSlugDependenciesService.curatedLibrariesOfSlug(SlugName, Labelled(SlugVersion))).thenReturn(
        Future.successful(
          Some(List(DependencyWithLatestVersionNoRuleViolations, DependencyWithRuleViolationsNoLatestVersion))
        )
      )

      val result = boot.controller.dependenciesOfSlug(SlugName, Some(SlugVersion.toString)).apply(FakeRequest())

      contentAsJson(result) shouldBe Json.parse(
        s"""[$JsonForDependencyWithLatestVersionNoRuleViolations, $JsonForDependencyWithRuleViolationsNoLatestVersion]"""
      )
    }

    "should get dependencies of the latest slug version using the service when a version is not supplied" in new GetDependenciesOfSlugFixture {
      when(boot.mockedSlugDependenciesService.curatedLibrariesOfSlug(SlugName, Latest)).thenReturn(
        Future.successful(
          Some(List(DependencyWithLatestVersionNoRuleViolations, DependencyWithRuleViolationsNoLatestVersion))
        )
      )

      val result = boot.controller.dependenciesOfSlug(SlugName, version = None).apply(FakeRequest())

      contentAsJson(result) shouldBe Json.parse(
        s"""[$JsonForDependencyWithLatestVersionNoRuleViolations, $JsonForDependencyWithRuleViolationsNoLatestVersion]"""
      )
    }

    "should return Not Found when the requested slug is not recognised" in new GetDependenciesOfSlugFixture {
      when(boot.mockedSlugDependenciesService.curatedLibrariesOfSlug(SlugName, Latest)).thenReturn(
        Future.successful(None)
      )

      val result = boot.controller.dependenciesOfSlug(SlugName, version = None).apply(FakeRequest())

      status(result) shouldBe NOT_FOUND
    }

    "should reject an invalid version descriptor" in new GetDependenciesOfSlugFixture {
      val InvalidVersion = "an-invalid-version"

      val result = boot.controller.dependenciesOfSlug(SlugName, Some(InvalidVersion)).apply(FakeRequest())

      status(result) shouldBe BAD_REQUEST
    }
  }

  case class Boot(
    mockedDependencyDataUpdatingService: DependencyDataUpdatingService,
    mockedTeamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
    mockedSlugInfoService: SlugInfoService,
    mockedSlugDependenciesService: SlugDependenciesService,
    mockServiceConfigsService: ServiceConfigsService,
    controller: ServiceDependenciesController)

  object Boot {
    def init: Boot = {
      val mockedDependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val mockedTeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
      val mockedSlugInfoService               = mock[SlugInfoService]
      val mockedSlugDependenciesService       = mock[SlugDependenciesService]
      val mockServiceConfigsService           = mock[ServiceConfigsService]
      val controller = new ServiceDependenciesController(
        Configuration(),
        mockedDependencyDataUpdatingService,
        mockedTeamsAndRepositoriesConnector,
        mockedSlugInfoService,
        mockedSlugDependenciesService,
        mock[ServiceDependenciesConfig],
        mockServiceConfigsService,
        stubControllerComponents()
      )
      Boot(
        mockedDependencyDataUpdatingService,
        mockedTeamsAndRepositoriesConnector,
        mockedSlugInfoService,
        mockedSlugDependenciesService,
        mockServiceConfigsService,
        controller)
    }
  }

  private trait GetDependenciesOfSlugFixture {
    val SlugName = "a-slug-name"
    val boot = Boot.init

    private val today = LocalDate.of(2019, 11, 27)
    val DependencyWithLatestVersionNoRuleViolations = Dependency(name = "library1", currentVersion = Version("1.1.1"),
      latestVersion = Some(Version("1.2.1")), bobbyRuleViolations = Nil)
    val DependencyWithRuleViolationsNoLatestVersion = Dependency(name = "library2", currentVersion = Version("2.2.2"),
      latestVersion = None, bobbyRuleViolations = List(
        DependencyBobbyRule(reason = "security vulnerability", from = today, range = BobbyVersionRange("(,3.0.0)"))
      )
    )

    val JsonForDependencyWithLatestVersionNoRuleViolations: String =
      s"""|{
          |  "name": "library1",
          |  "currentVersion": {"major": 1, "minor": 1, "patch": 1, "original": "1.1.1"},
          |  "latestVersion": {"major": 1, "minor": 2, "patch": 1, "original": "1.2.1"},
          |  "bobbyRuleViolations": [],
          |  "isExternal": false
          |}""".stripMargin

    val JsonForDependencyWithRuleViolationsNoLatestVersion: String =
      s"""|{
          |  "name": "library2",
          |  "currentVersion": {"major": 2, "minor": 2, "patch": 2, "original": "2.2.2"},
          |  "bobbyRuleViolations": [{"reason": "security vulnerability", "from": "2019-11-27", "range": "(,3.0.0)"}],
          |  "isExternal": false
          |}""".stripMargin
  }
}
