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
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.servicedependencies.config.CuratedDependencyConfigProvider
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.connector.{ArtifactoryConnector, GithubConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class DependencyDataUpdatingServiceSpec
  extends AnyFunSpec
     with MockitoSugar
     with Matchers
     with GuiceOneAppPerTest
     with OptionValues
     with MongoSupport
     with IntegrationPatience {

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  private val timeForTest = Instant.now()

  private val curatedDependencyConfig = CuratedDependencyConfig(
    sbtPlugins = Nil
  , libraries  = Nil
  , others     = Nil
  )

  describe("reloadLibraryVersions") {

    it("should call the library update function on the repository") {

      val boot = new Boot(CuratedDependencyConfig(
        sbtPlugins = Nil
      , libraries  = List(DependencyConfig(name = "libYY", group= "uk.gov.hmrc", latestVersion = None))
      , others     = Nil
      ))

      when(boot.mockArtifactoryConnector.findLatestVersion(group = "uk.gov.hmrc", artefact = "libYY"))
        .thenReturn(Future.successful(Some(Version(1, 1, 1))))

      when(boot.mockLibraryVersionRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoDependencyVersion]))

      boot.dependencyUpdatingService.reloadLatestLibraryVersions.futureValue

      verify(boot.mockLibraryVersionRepository, times(1))
        .update(MongoDependencyVersion(name = "libYY", group = "uk.gov.hmrc", version = Some(Version(1, 1, 1)), updateDate = timeForTest))
      verifyZeroInteractions(boot.mockRepositoryLibraryDependenciesRepository)
    }
  }

  describe("reloadSbtPluginVersions") {

    it("should call the sbt plugin update function on the repository") {

      val boot = new Boot(CuratedDependencyConfig(
        sbtPlugins = List(DependencyConfig(name = "sbtPlugin123", group= "uk.gov.hmrc", latestVersion = None))
      , libraries  = Nil
      , others     = Nil
      ))

      when(boot.mockArtifactoryConnector.findLatestVersion(group = "uk.gov.hmrc", artefact = "sbtPlugin123"))
        .thenReturn(Future.successful(Some(Version(1, 1, 1))))

      when(boot.mockSbtPluginVersionRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoDependencyVersion]))

      boot.dependencyUpdatingService.reloadLatestSbtPluginVersions.futureValue

      verify(boot.mockSbtPluginVersionRepository, times(1))
        .update(MongoDependencyVersion(name = "sbtPlugin123", group = "uk.gov.hmrc", version = Some(Version(1, 1, 1)), updateDate = timeForTest))
      verifyZeroInteractions(boot.mockRepositoryLibraryDependenciesRepository)
      verifyZeroInteractions(boot.mockLibraryVersionRepository)
    }
  }

  describe("reloadMongoRepositoryDependencyDataForAllRepositories") {

    def testReloadCurrentDependenciesDataForAllRepositories(
      force            : Boolean
    , repoLastUpdatedAt: Instant
    , shouldUpdate     : Boolean
    ) = {
      val boot = new Boot(curatedDependencyConfig)

      val repositoryName = "repoXyz"

      val mongoRepositoryDependencies =
        MongoRepositoryDependencies(repositoryName, Nil, Nil, Nil, updateDate = timeForTest)

      val repositoryInfo =
        RepositoryInfo(
          name          = repositoryName
        , createdAt     = Instant.EPOCH
        , lastUpdatedAt = repoLastUpdatedAt
        )

      when(boot.mockRepositoryLibraryDependenciesRepository.getAllEntries)
        .thenReturn(Future.successful(Seq(mongoRepositoryDependencies)))

      when(boot.mockTeamsAndRepositoriesConnector.getAllRepositories(any()))
        .thenReturn(Future.successful(Seq(repositoryInfo)))

      when(boot.mockGithubConnector.buildDependencies(any(), any()))
        .thenReturn(Some(mongoRepositoryDependencies))

      when(boot.mockRepositoryLibraryDependenciesRepository.update(any()))
        .thenReturn(Future.successful(mongoRepositoryDependencies))

      val res = boot.dependencyUpdatingService
        .reloadCurrentDependenciesDataForAllRepositories(force)(HeaderCarrier())
        .futureValue

      if (shouldUpdate) {
        res shouldBe Seq(mongoRepositoryDependencies)
        verify(boot.mockRepositoryLibraryDependenciesRepository, times(1))
          .update(eqTo(mongoRepositoryDependencies))
      } else {
        res shouldBe Nil
        verify(boot.mockRepositoryLibraryDependenciesRepository, Mockito.never())
          .update(any())
      }
    }

    it("should call the dependency update function to persist the dependencies if repo has been modified") {
      testReloadCurrentDependenciesDataForAllRepositories(
        force             = false
      , repoLastUpdatedAt = timeForTest
      , shouldUpdate      = true
      )
    }
    it("should not call the dependency update function to persist the dependencies if repo has not been modified") {
      testReloadCurrentDependenciesDataForAllRepositories(
        force             = false
      , repoLastUpdatedAt = Instant.EPOCH
      , shouldUpdate      = false
      )
    }

    it("should call the dependency update function to persist the dependencies if repo has not been modified but force") {
      testReloadCurrentDependenciesDataForAllRepositories(
        force             = true
      , repoLastUpdatedAt = Instant.EPOCH
      , shouldUpdate      = true
      )
    }
  }

  describe("getDependencyVersionsForRepository") {
    it("should return the current and latest library dependency versions for a repository") {
      val boot = new Boot(curatedDependencyConfig)

      val libraryDependencies = Seq(
        MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0)),
        MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(repositoryName, libraryDependencies, Nil, Nil, timeForTest))))

      val referenceLibraryVersions = Seq(
        MongoDependencyVersion(name = "lib1", group = "uk.gov.hmrc", version = Some(Version(1, 1, 0)))
      , MongoDependencyVersion(name = "lib2", group = "uk.gov.hmrc", version = Some(Version(2, 1, 0)))
      )
      when(boot.mockLibraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceLibraryVersions))

      when(boot.mockSbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe Dependencies(
        repositoryName = repositoryName,
        libraryDependencies = Seq(
          Dependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0), latestVersion = Some(Version(1, 1, 0)), bobbyRuleViolations = List.empty),
          Dependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0), latestVersion = Some(Version(2, 1, 0)), bobbyRuleViolations = List.empty)
        ),
        sbtPluginsDependencies = Nil,
        otherDependencies      = Nil,
        timeForTest
      )
    }

    it("should return the current and latest sbt plugin dependency versions for a repository") {
      val boot = new Boot(curatedDependencyConfig)

      val sbtPluginDependencies = Seq(
        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0)),
        MongoRepositoryDependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(repositoryName, Nil, sbtPluginDependencies, Nil, timeForTest))))

      val referenceSbtPluginVersions = Seq(
        MongoDependencyVersion(name = "plugin1", group = "uk.gov.hmrc", version = Some(Version(3, 1, 0)))
      , MongoDependencyVersion(name = "plugin2", group = "uk.gov.hmrc", version = Some(Version(4, 1, 0)))
      )

      when(boot.mockSbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceSbtPluginVersions))

      when(boot.mockLibraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe Dependencies(
        repositoryName,
        Nil,
        Seq(
          Dependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0), latestVersion = Some(Version(3, 1, 0)), bobbyRuleViolations = List.empty),
          Dependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0), latestVersion = Some(Version(4, 1, 0)), bobbyRuleViolations = List.empty)
        ),
        Nil,
        timeForTest
      )
    }

    it("should return the current and latest external sbt plugin dependency versions for a repository") {
      val boot = new Boot(curatedDependencyConfig)

      val sbtPluginDependencies = Seq(
        MongoRepositoryDependency(name = "internal-plugin", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0)),
        MongoRepositoryDependency(name = "external-plugin", group = "uk.edu"     , currentVersion = Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(repositoryName, Nil, sbtPluginDependencies, Nil, timeForTest))))

      val referenceSbtPluginVersions = Seq(
        MongoDependencyVersion(name = "internal-plugin", group = "uk.gov.hmrc", version = Some(Version(3, 1, 0)))
      , MongoDependencyVersion(name = "external-plugin", group = "uk.edu"     , version = Some(Version(11, 22, 33)))
      )

      when(boot.mockSbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceSbtPluginVersions))

      when(boot.mockLibraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val optDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      optDependencies shouldBe Some(Dependencies(
        repositoryName         = repositoryName,
        libraryDependencies    = Nil,
        sbtPluginsDependencies = Seq(
          Dependency(name = "internal-plugin", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0), latestVersion = Some(Version(3, 1, 0))   , bobbyRuleViolations = List.empty)
        , Dependency(name = "external-plugin", group = "uk.edu"     , currentVersion = Version(2, 0, 0), latestVersion = Some(Version(11, 22, 33)), bobbyRuleViolations = List.empty)
        ),
        otherDependencies = Nil,
        timeForTest
      ))

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)
    }

    it("test for none") {
      val boot = new Boot(curatedDependencyConfig)

      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(None))

      when(boot.mockLibraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      when(boot.mockSbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies shouldBe None
    }

    it("test for non existing latest") {
      val boot = new Boot(curatedDependencyConfig)

      val libraryDependencies = Seq(
        MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0)),
        MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(repositoryName, libraryDependencies, Nil, Nil, timeForTest))))

      when(boot.mockLibraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      when(boot.mockSbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe Dependencies(
        repositoryName = repositoryName,
        libraryDependencies = Seq(
          Dependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0), latestVersion = None, bobbyRuleViolations = List.empty),
          Dependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0), latestVersion = None, bobbyRuleViolations = List.empty)
        ),
        sbtPluginsDependencies = Nil,
        otherDependencies      = Nil,
        timeForTest
      )
    }
  }

  describe("getDependencyVersionsForAllRepositories") {
    it("should return the current and latest library, sbt plugin and other dependency versions for all repositories") {
      val boot = new Boot(
        curatedDependencyConfig.copy(others =
          List(DependencyConfig(name = "sbt", group = "org.scala-sbt", latestVersion = Some(Version(100, 10, 1))))
        )
      )

      val libraryDependencies1 = Seq(
        MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 1, 0)),
        MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(1, 2, 0))
      )
      val libraryDependencies2 = Seq(
        MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(2, 1, 0)),
        MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 2, 0))
      )

      val sbtPluginDependencies1 = Seq(
        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(10, 1, 0)),
        MongoRepositoryDependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(10, 2, 0))
      )

      val sbtPluginDependencies2 = Seq(
        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(20, 1, 0)),
        MongoRepositoryDependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(20, 2, 0))
      )

      val otherDependencies1 = Seq(
        MongoRepositoryDependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(0, 13, 1))
      )

      val otherDependencies2 = Seq(
        MongoRepositoryDependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(0, 13, 2))
      )

      val referenceLibraryVersions = Seq(
        MongoDependencyVersion(name = "lib1", group = "uk.gov.hmrc", version = Some(Version(3, 0, 0)))
      , MongoDependencyVersion(name = "lib2", group = "uk.gov.hmrc", version = Some(Version(4, 0, 0)))
      )

      val referenceSbtPluginVersions = Seq(
        MongoDependencyVersion(name = "plugin1", group = "uk.gov.hmrc", version = Some(Version(30, 0, 0)))
      , MongoDependencyVersion(name = "plugin2", group = "uk.gov.hmrc", version = Some(Version(40, 0, 0)))
      )

      val repository1 = "repo1"
      val repository2 = "repo2"

      when(boot.mockRepositoryLibraryDependenciesRepository.getAllEntries)
        .thenReturn(Future.successful(Seq(
          MongoRepositoryDependencies(
            repository1,
            libraryDependencies1,
            sbtPluginDependencies1,
            otherDependencies1,
            timeForTest),
          MongoRepositoryDependencies(
            repository2,
            libraryDependencies2,
            sbtPluginDependencies2,
            otherDependencies2,
            timeForTest)
        )))

      when(boot.mockLibraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceLibraryVersions))

      when(boot.mockSbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceSbtPluginVersions))

      val maybeDependencies = boot.dependencyUpdatingService.getDependencyVersionsForAllRepositories.futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getAllEntries

      maybeDependencies should contain theSameElementsAs Seq(
        Dependencies(
          repositoryName = repository1,
          libraryDependencies = Seq(
            Dependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 1, 0), latestVersion = Some(Version(3, 0, 0)), bobbyRuleViolations = List.empty),
            Dependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(1, 2, 0), latestVersion = Some(Version(4, 0, 0)), bobbyRuleViolations = List.empty)
          ),
          sbtPluginsDependencies = Seq(
            Dependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(10, 1, 0), latestVersion = Some(Version(30, 0, 0)), bobbyRuleViolations = List.empty),
            Dependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(10, 2, 0), latestVersion = Some(Version(40, 0, 0)), bobbyRuleViolations = List.empty)
          ),
          otherDependencies = Seq(
            Dependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(0, 13, 1), latestVersion = Some(Version(100, 10, 1)), bobbyRuleViolations = List.empty)
          ),
          timeForTest
        ),
        Dependencies(
          repositoryName = repository2,
          libraryDependencies = Seq(
            Dependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(2, 1, 0), latestVersion = Some(Version(3, 0, 0)), bobbyRuleViolations = List.empty),
            Dependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 2, 0), latestVersion = Some(Version(4, 0, 0)), bobbyRuleViolations = List.empty)
          ),
          sbtPluginsDependencies = Seq(
            Dependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(20, 1, 0), latestVersion = Some(Version(30, 0, 0)), bobbyRuleViolations = List.empty),
            Dependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(20, 2, 0), latestVersion = Some(Version(40, 0, 0)), bobbyRuleViolations = List.empty)
          ),
          otherDependencies = Seq(
            Dependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(0, 13, 2), latestVersion = Some(Version(100, 10, 1)), bobbyRuleViolations = List.empty)
          ),
          timeForTest
        )
      )
    }
  }

  class Boot(
    dependencyConfig: CuratedDependencyConfig
  ) {

    val mockCuratedDependencyConfigProvider = mock[CuratedDependencyConfigProvider]
    when(mockCuratedDependencyConfigProvider.curatedDependencyConfig)
      .thenReturn(dependencyConfig)

    val mockRepositoryLibraryDependenciesRepository = mock[RepositoryLibraryDependenciesRepository]
    val mockLibraryVersionRepository                = mock[LibraryVersionRepository]
    val mockSbtPluginVersionRepository              = mock[SbtPluginVersionRepository]
    val mockTeamsAndRepositoriesConnector           = mock[TeamsAndRepositoriesConnector]
    val mockArtifactoryConnector                    = mock[ArtifactoryConnector]
    val mockGithubConnector                         = mock[GithubConnector]

    val dependencyUpdatingService = new DependencyDataUpdatingService(
        mockCuratedDependencyConfigProvider
      , mockRepositoryLibraryDependenciesRepository
      , mockLibraryVersionRepository
      , mockSbtPluginVersionRepository
      , mockTeamsAndRepositoriesConnector
      , mockArtifactoryConnector
      , mockGithubConnector
      ) {
        override def now: Instant = timeForTest
      }
  }
}
