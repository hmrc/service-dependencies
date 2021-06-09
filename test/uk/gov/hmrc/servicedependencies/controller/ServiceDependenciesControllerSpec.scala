/*
 * Copyright 2021 HM Revenue & Customs
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
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency, DependencyBobbyRule}
import uk.gov.hmrc.servicedependencies.model.{BobbyRules, BobbyVersionRange, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.service._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ServiceDependenciesControllerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  "getDependencyVersionsForRepository" should {
    "get dependency versions for a repository using the service" in {
      val boot                   = Boot.init
      val repoName               = "repo1"
      val now                    = Instant.now()
      val repositoryDependencies = Dependencies(repoName, Seq(), Seq(), Seq(), now)

      when(boot.mockRepositoryDependenciesService.getDependencyVersionsForRepository(any()))
        .thenReturn(Future.successful(Some(repositoryDependencies)))

      when(boot.mockServiceConfigsConnector.getBobbyRules)
        .thenReturn(Future.successful(BobbyRules(Map.empty)))

      val result = boot.controller
        .getDependencyVersionsForRepository(repoName)
        .apply(FakeRequest())

      contentAsJson(result).toString shouldBe
        s"""{"repositoryName":"repo1","libraryDependencies":[],"sbtPluginsDependencies":[],"otherDependencies":[],"lastUpdated":"$now"}"""

      Mockito.verify(boot.mockRepositoryDependenciesService)
        .getDependencyVersionsForRepository(repoName)
    }
  }

  "get dependencies" should {
    "get all dependencies using the service" in {
      val boot                = Boot.init
      val now                 = Instant.now()
      val repo1               = Dependencies("repo1", Seq(), Seq(), Seq(), now)
      val repo2               = Dependencies("repo2", Seq(), Seq(), Seq(), now)
      val repo3               = Dependencies("repo3", Seq(), Seq(), Seq(), now)
      val libraryDependencies = Seq(repo1, repo2, repo3)

      when(boot.mockRepositoryDependenciesService.getDependencyVersionsForAllRepositories)
        .thenReturn(Future.successful(libraryDependencies))

      when(boot.mockServiceConfigsConnector.getBobbyRules)
        .thenReturn(Future.successful(BobbyRules(Map.empty)))

      val result = boot.controller.dependencies().apply(FakeRequest())

      contentAsJson(result).toString shouldBe
        List( s"""{"repositoryName":"repo1","libraryDependencies":[],"sbtPluginsDependencies":[],"otherDependencies":[],"lastUpdated":"$now"}"""
            , s"""{"repositoryName":"repo2","libraryDependencies":[],"sbtPluginsDependencies":[],"otherDependencies":[],"lastUpdated":"$now"}"""
            , s"""{"repositoryName":"repo3","libraryDependencies":[],"sbtPluginsDependencies":[],"otherDependencies":[],"lastUpdated":"$now"}"""
            ).mkString("[", ",", "]")
    }
  }

  "dependenciesOfSlug" should {
    "get dependencies for a SlugInfoFlag" in new GetDependenciesOfSlugFixture {
      val flag = SlugInfoFlag.Latest
      when(boot.mockSlugDependenciesService.curatedLibrariesOfSlug(slugName, flag)).thenReturn(
        Future.successful(
          Some(List(DependencyWithLatestVersionNoRuleViolations, DependencyWithRuleViolationsNoLatestVersion))
        )
      )

      val result = boot.controller.dependenciesOfSlug(slugName, flag.asString).apply(FakeRequest())

      contentAsJson(result) shouldBe Json.parse(
        s"""[$jsonForDependencyWithLatestVersionNoRuleViolations, $jsonForDependencyWithRuleViolationsNoLatestVersion]"""
      )
    }

    "return Not Found when the requested slug is not recognised" in new GetDependenciesOfSlugFixture {
      val flag = SlugInfoFlag.Latest
      when(boot.mockSlugDependenciesService.curatedLibrariesOfSlug(slugName, flag)).thenReturn(
        Future.successful(None)
      )

      val result = boot.controller.dependenciesOfSlug(slugName, flag.asString).apply(FakeRequest())

      status(result) shouldBe NOT_FOUND
    }

    "reject an invalid flag descriptor" in new GetDependenciesOfSlugFixture {
      val invalidFlag = "an-invalid-flag"

      val result = boot.controller.dependenciesOfSlug(slugName, invalidFlag).apply(FakeRequest())

      status(result) shouldBe BAD_REQUEST
    }
  }

  case class Boot(
      mockSlugInfoService              : SlugInfoService
    , mockSlugDependenciesService      : SlugDependenciesService
    , mockServiceConfigsConnector      : ServiceConfigsConnector
    , mockTeamDependencyService        : TeamDependencyService
    , mockRepositoryDependenciesService: RepositoryDependenciesService
    , controller                       : ServiceDependenciesController
    )

  object Boot {
    def init: Boot = {
      val mockSlugInfoService               = mock[SlugInfoService]
      val mockSlugDependenciesService       = mock[SlugDependenciesService]
      val mockServiceConfigsConnector       = mock[ServiceConfigsConnector]
      val mockTeamDependencyService         = mock[TeamDependencyService]
      val mockRepositoryDependenciesService = mock[RepositoryDependenciesService]
      val controller = new ServiceDependenciesController(
          mockSlugInfoService
        , mockSlugDependenciesService
        , mockServiceConfigsConnector
        , mockTeamDependencyService
        , mockRepositoryDependenciesService
        , stubControllerComponents()
        )
      Boot(
          mockSlugInfoService
        , mockSlugDependenciesService
        , mockServiceConfigsConnector
        , mockTeamDependencyService
        , mockRepositoryDependenciesService
        , controller
        )
    }
  }

  private trait GetDependenciesOfSlugFixture {
    val slugName = "a-slug-name"
    val boot = Boot.init

    private val today = LocalDate.of(2019, 11, 27)

    val DependencyWithLatestVersionNoRuleViolations = Dependency(
        name                = "library1"
      , group               = "uk.gov.hmrc"
      , currentVersion      = Version("1.1.1")
      , latestVersion       = Some(Version("1.2.1"))
      , bobbyRuleViolations = Nil
      )

    val DependencyWithRuleViolationsNoLatestVersion = Dependency(
        name                = "library2"
      , group               = "uk.gov.hmrc"
      , currentVersion      = Version("2.2.2")
      , latestVersion       = None
      , bobbyRuleViolations = List(
          DependencyBobbyRule(reason = "security vulnerability", from = today, range = BobbyVersionRange("(,3.0.0)"))
        )
      )

    val jsonForDependencyWithLatestVersionNoRuleViolations: String =
      s"""|{
          |  "name": "library1",
          |  "group": "uk.gov.hmrc",
          |  "currentVersion": {"major": 1, "minor": 1, "patch": 1, "original": "1.1.1"},
          |  "latestVersion": {"major": 1, "minor": 2, "patch": 1, "original": "1.2.1"},
          |  "bobbyRuleViolations": [],
          |  "isExternal": false
          |}""".stripMargin

    val jsonForDependencyWithRuleViolationsNoLatestVersion: String =
      s"""|{
          |  "name": "library2",
          |  "group": "uk.gov.hmrc",
          |  "currentVersion": {"major": 2, "minor": 2, "patch": 2, "original": "2.2.2"},
          |  "bobbyRuleViolations": [{"reason": "security vulnerability", "from": "2019-11-27", "range": "(,3.0.0)"}],
          |  "isExternal": false
          |}""".stripMargin
  }
}
