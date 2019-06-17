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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Configuration}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.controller.model.Dependencies
import uk.gov.hmrc.servicedependencies.service._
import uk.gov.hmrc.time.DateTimeUtils
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

class ServiceDependenciesControllerSpec
    extends FreeSpec
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
      val now                    = DateTimeUtils.now
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
      val now                 = DateTimeUtils.now
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

  case class Boot(
    mockedDependencyDataUpdatingService: DependencyDataUpdatingService,
    mockedTeamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
    mockedSlugInfoService: SlugInfoService,
    mockServiceConfigsService: ServiceConfigsService,
    controller: ServiceDependenciesController)

  object Boot {
    def init: Boot = {
      val mockedDependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val mockedTeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
      val mockedSlugInfoService               = mock[SlugInfoService]
      val mockServiceConfigsService           = mock[ServiceConfigsService]
      val controller = new ServiceDependenciesController(
        Configuration(),
        mockedDependencyDataUpdatingService,
        mockedTeamsAndRepositoriesConnector,
        mockedSlugInfoService,
        mock[ServiceDependenciesConfig],
        mockServiceConfigsService,
        stubControllerComponents()
      )
      Boot(
        mockedDependencyDataUpdatingService,
        mockedTeamsAndRepositoriesConnector,
        mockedSlugInfoService,
        mockServiceConfigsService,
        controller)
    }
  }
}
