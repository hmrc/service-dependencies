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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.{Mockito, MockitoSugar}
import org.scalatest.OptionValues
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.connector.{ArtifactoryConnector, GithubConnector, GithubSearchResults, ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DependencyDataUpdatingServiceSpec
  extends AnyFunSpec
     with MockitoSugar
     with Matchers
     with OptionValues
     with MongoSupport
     with IntegrationPatience {

  private val timeForTest = Instant.now()

  describe("reloadLatestVersions") {
    it("should call the dependency version update function on the repository") {
      val boot = new Boot(CuratedDependencyConfig(
        sbtPlugins = Nil
      , libraries  = List(DependencyConfig(name = "libYY", group= "uk.gov.hmrc", latestVersion = None))
      , others     = Nil
      ))

      when(boot.mockArtifactoryConnector.findLatestVersion(group = "uk.gov.hmrc", artefact = "libYY"))
        .thenReturn(Future.successful(Map(ScalaVersion.SV_None -> Version("1.1.1"))))

      when(boot.mockLatestVersionRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoLatestVersion]))

      boot.dependencyUpdatingService.reloadLatestVersions().futureValue

      verify(boot.mockLatestVersionRepository, times(1))
        .update(MongoLatestVersion(name = "libYY", group = "uk.gov.hmrc", version = Version("1.1.1"), updateDate = timeForTest))
      verifyZeroInteractions(boot.mockRepositoryDependenciesRepository)
    }
  }

  describe("reloadMongoRepositoryDependencyDataForAllRepositories") {

    def testReloadCurrentDependenciesDataForAllRepositories(
      repoLastUpdatedAt: Instant
    , shouldUpdate     : Boolean
    ) = {
      val boot = new Boot(CuratedDependencyConfig(
        sbtPlugins = List.empty
      , libraries  = List.empty
      , others     = List.empty
      ))

      val repositoryName = "repoXyz"

      val mongoRepositoryDependencies =
        MongoRepositoryDependencies(repositoryName, Nil, Nil, Nil, updateDate = timeForTest)

      val githubSearchResults =
        GithubSearchResults(
            sbtPlugins = Nil
          , libraries = Nil
          , others    = Nil
          )

      val repositoryInfo =
        RepositoryInfo(
          name          = repositoryName
        , createdAt     = Instant.EPOCH
        , lastUpdatedAt = repoLastUpdatedAt
        )

      when(boot.mockRepositoryDependenciesRepository.getAllEntries)
        .thenReturn(Future.successful(Seq(mongoRepositoryDependencies)))

      when(boot.mockTeamsAndRepositoriesConnector.getAllRepositories(archived = eqTo(Some(false)))(any()))
        .thenReturn(Future.successful(Seq(repositoryInfo)))

      when(boot.mockGithubConnector.findVersionsForMultipleArtifacts(any()))
        .thenReturn(Right(githubSearchResults))

      when(boot.mockRepositoryDependenciesRepository.update(any()))
        .thenReturn(Future.successful(mongoRepositoryDependencies))

      val res = boot.dependencyUpdatingService
        .reloadCurrentDependenciesDataForAllRepositories(HeaderCarrier())
        .futureValue

      if (shouldUpdate) {
        res shouldBe Seq(mongoRepositoryDependencies)
        verify(boot.mockRepositoryDependenciesRepository, times(1))
          .update(eqTo(mongoRepositoryDependencies))
      } else {
        res shouldBe Nil
        verify(boot.mockRepositoryDependenciesRepository, Mockito.never())
          .update(any())
      }
    }

    it("should call the dependency update function to persist the dependencies if repo has been modified") {
      testReloadCurrentDependenciesDataForAllRepositories(
        repoLastUpdatedAt = timeForTest
      , shouldUpdate      = true
      )
    }

    it("should not call the dependency update function to persist the dependencies if repo has not been modified") {
      testReloadCurrentDependenciesDataForAllRepositories(
        repoLastUpdatedAt = Instant.EPOCH
      , shouldUpdate      = false
      )
    }
  }

  class Boot(dependencyConfig: CuratedDependencyConfig) {
    val mockServiceDependenciesConfig        = mock[ServiceDependenciesConfig]
    val mockRepositoryDependenciesRepository = mock[RepositoryDependenciesRepository]
    val mockLatestVersionRepository          = mock[LatestVersionRepository]
    val mockTeamsAndRepositoriesConnector    = mock[TeamsAndRepositoriesConnector]
    val mockArtifactoryConnector             = mock[ArtifactoryConnector]
    val mockGithubConnector                  = mock[GithubConnector]
    val mockServiceConfigsConnector          = mock[ServiceConfigsConnector]

    when(mockServiceDependenciesConfig.curatedDependencyConfig)
      .thenReturn(dependencyConfig)

    when(mockServiceConfigsConnector.getBobbyRules)
      .thenReturn(Future.successful(BobbyRules(Map.empty)))

    val dependencyUpdatingService = new DependencyDataUpdatingService(
        mockServiceDependenciesConfig
      , mockRepositoryDependenciesRepository
      , mockLatestVersionRepository
      , mockTeamsAndRepositoriesConnector
      , mockArtifactoryConnector
      , mockGithubConnector
      , mockServiceConfigsConnector
      ) {
        override def now: Instant = timeForTest
      }
  }
}
