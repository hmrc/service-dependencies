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
import uk.gov.hmrc.servicedependencies.connector.{ArtifactoryConnector, Github, GithubSearchResults, TeamsAndRepositoriesConnector}
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

  describe("reloadDependencyVersions") {

    it("should call the dependency version update function on the repository") {

      val boot = new Boot(CuratedDependencyConfig(
        sbtPlugins = Nil
      , libraries  = List(DependencyConfig(name = "libYY", group= "uk.gov.hmrc", latestVersion = None))
      , others     = Nil
      ))

      when(boot.mockArtifactoryConnector.findLatestVersion(group = "uk.gov.hmrc", artefact = "libYY"))
        .thenReturn(Future.successful(Map(ScalaVersion.SV_None -> Version(1, 1, 1))))

      when(boot.mockDependencyVersionRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoDependencyVersion]))

      boot.dependencyUpdatingService.reloadLatestDependencyVersions(HeaderCarrier()).futureValue

      verify(boot.mockDependencyVersionRepository, times(1))
        .update(MongoDependencyVersion(name = "libYY", group = "uk.gov.hmrc", version = Version(1, 1, 1), updateDate = timeForTest))
      verifyZeroInteractions(boot.mockRepositoryLibraryDependenciesRepository)
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

      when(boot.mockRepositoryLibraryDependenciesRepository.getAllEntries)
        .thenReturn(Future.successful(Seq(mongoRepositoryDependencies)))

      when(boot.mockTeamsAndRepositoriesConnector.getAllRepositories(any()))
        .thenReturn(Future.successful(Seq(repositoryInfo)))

      when(boot.mockGithub.findVersionsForMultipleArtifacts(any()))
        .thenReturn(Right(githubSearchResults))

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

      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(
            repositoryName        = "repoXYZ"
          , libraryDependencies   = Seq(
                                      MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0))
                                    , MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0))
                                    )
          , sbtPluginDependencies = Nil
          , otherDependencies     = Nil
          , updateDate            = timeForTest
          ))
        ))

      val referenceLibraryVersions = Seq(
        MongoDependencyVersion(name = "lib1", group = "uk.gov.hmrc", version = Version(1, 1, 0))
      , MongoDependencyVersion(name = "lib2", group = "uk.gov.hmrc", version = Version(2, 1, 0))
      )
      when(boot.mockDependencyVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceLibraryVersions))

      val optDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      optDependencies shouldBe Some(Dependencies(
          repositoryName         = "repoXYZ"
        , libraryDependencies    = Seq(
                                     Dependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0), latestVersion = Some(Version(1, 1, 0)), bobbyRuleViolations = Nil)
                                   , Dependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0), latestVersion = Some(Version(2, 1, 0)), bobbyRuleViolations = Nil)
                                   )
        , sbtPluginsDependencies = Nil
        , otherDependencies      = Nil
        , timeForTest
        ))
    }

    it("should return the current and latest sbt plugin dependency versions for a repository") {
      val boot = new Boot(curatedDependencyConfig)

      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(
              repositoryName        = repositoryName
            , libraryDependencies   = Nil
            , sbtPluginDependencies = Seq(
                                        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0))
                                      , MongoRepositoryDependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0))
                                      )
            , otherDependencies     = Nil
            , updateDate            = timeForTest
            ))
        ))

      when(boot.mockDependencyVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Seq(
            MongoDependencyVersion(name = "plugin1", group = "uk.gov.hmrc", version = Version(3, 1, 0))
          , MongoDependencyVersion(name = "plugin2", group = "uk.gov.hmrc", version = Version(4, 1, 0))
          )
        ))

      val optDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      optDependencies shouldBe Some(Dependencies(
          repositoryName         = repositoryName
        , libraryDependencies    = Nil
        , sbtPluginsDependencies = Seq(
                                     Dependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0), latestVersion = Some(Version(3, 1, 0)), bobbyRuleViolations = Nil)
                                   , Dependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0), latestVersion = Some(Version(4, 1, 0)), bobbyRuleViolations = Nil)
                                   )
        , otherDependencies      = Nil
        , lastUpdated            = timeForTest
      ))
    }

    it("should return the current and latest external sbt plugin dependency versions for a repository") {
      val boot = new Boot(curatedDependencyConfig)

      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(
              repositoryName        = repositoryName
            , libraryDependencies   = Nil
            , sbtPluginDependencies = Seq(
                                        MongoRepositoryDependency(name = "internal-plugin", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0))
                                      , MongoRepositoryDependency(name = "external-plugin", group = "uk.edu"     , currentVersion = Version(2, 0, 0))
                                      )
            , otherDependencies     = Nil
            , updateDate            = timeForTest
            ))
        ))

      when(boot.mockDependencyVersionRepository.getAllEntries)
        .thenReturn(
          Future.successful(Seq(
              MongoDependencyVersion(name = "internal-plugin", group = "uk.gov.hmrc", version = Version(3, 1, 0))
            , MongoDependencyVersion(name = "external-plugin", group = "uk.edu"     , version = Version(11, 22, 33))
            ))
        )

      val optDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      optDependencies shouldBe Some(Dependencies(
          repositoryName         = repositoryName
        , libraryDependencies    = Nil
        , sbtPluginsDependencies = Seq(
                                     Dependency(name = "internal-plugin", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0), latestVersion = Some(Version(3, 1, 0))   , bobbyRuleViolations = Nil)
                                   , Dependency(name = "external-plugin", group = "uk.edu"     , currentVersion = Version(2, 0, 0), latestVersion = Some(Version(11, 22, 33)), bobbyRuleViolations = Nil)
                                   )
        , otherDependencies      = Nil
        , lastUpdated            = timeForTest
        ))

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)
    }

    it("test for none") {
      val boot = new Boot(curatedDependencyConfig)

      val repositoryName = "repoXYZ"

      when(boot.mockRepositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(None))

      when(boot.mockDependencyVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        boot.dependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies shouldBe None
    }
  }

  describe("getDependencyVersionsForAllRepositories") {
    it("should return the current and latest library, sbt plugin and other dependency versions for all repositories") {
      val boot = new Boot(curatedDependencyConfig)

      when(boot.mockRepositoryLibraryDependenciesRepository.getAllEntries)
        .thenReturn(Future.successful(Seq(
            MongoRepositoryDependencies(
                repositoryName        = "repo1"
              , libraryDependencies   = Seq(
                                          MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 1, 0))
                                        , MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(1, 2, 0))
                                        )
              , sbtPluginDependencies = Seq(
                                          MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(10, 1, 0))
                                        , MongoRepositoryDependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(10, 2, 0))
                                        )
              , otherDependencies     = Seq(
                                          MongoRepositoryDependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(0, 13, 1))
                                        )
              , updateDate            = timeForTest
              )
          , MongoRepositoryDependencies(
              repositoryName        = "repo2"
            , libraryDependencies   = Seq(
                                        MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(2, 1, 0))
                                      , MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 2, 0))
                                      )
            , sbtPluginDependencies = Seq(
                                        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(20, 1, 0))
                                      , MongoRepositoryDependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(20, 2, 0))
                                      )
            , otherDependencies     = Seq(
                                        MongoRepositoryDependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(0, 13, 2))
                                      )
            , updateDate            = timeForTest
            )
        )))

      when(boot.mockDependencyVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Seq(
            MongoDependencyVersion(name = "lib1"   , group = "uk.gov.hmrc"  , version = Version(3  , 0 , 0))
          , MongoDependencyVersion(name = "lib2"   , group = "uk.gov.hmrc"  , version = Version(4  , 0 , 0))
          , MongoDependencyVersion(name = "plugin1", group = "uk.gov.hmrc"  , version = Version(30 , 0 , 0))
          , MongoDependencyVersion(name = "plugin2", group = "uk.gov.hmrc"  , version = Version(40 , 0 , 0))
          , MongoDependencyVersion(name = "sbt"    , group = "org.scala-sbt", version = Version(100, 10, 1))
          )))

      val maybeDependencies = boot.dependencyUpdatingService.getDependencyVersionsForAllRepositories.futureValue

      verify(boot.mockRepositoryLibraryDependenciesRepository, times(1)).getAllEntries

      maybeDependencies should contain theSameElementsAs Seq(
          Dependencies(
            repositoryName = "repo1"
          , libraryDependencies = Seq(
              Dependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 1, 0), latestVersion = Some(Version(3, 0, 0)), bobbyRuleViolations = Nil)
            , Dependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(1, 2, 0), latestVersion = Some(Version(4, 0, 0)), bobbyRuleViolations = Nil)
            )
          , sbtPluginsDependencies = Seq(
              Dependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(10, 1, 0), latestVersion = Some(Version(30, 0, 0)), bobbyRuleViolations = Nil)
            , Dependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(10, 2, 0), latestVersion = Some(Version(40, 0, 0)), bobbyRuleViolations = Nil)
            )
          , otherDependencies = Seq(
              Dependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(0, 13, 1), latestVersion = Some(Version(100, 10, 1)), bobbyRuleViolations = Nil)
            )
          , timeForTest
          )
        , Dependencies(
            repositoryName = "repo2"
          , libraryDependencies = Seq(
              Dependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(2, 1, 0), latestVersion = Some(Version(3, 0, 0)), bobbyRuleViolations = Nil),
              Dependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 2, 0), latestVersion = Some(Version(4, 0, 0)), bobbyRuleViolations = Nil)
            )
          , sbtPluginsDependencies = Seq(
              Dependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(20, 1, 0), latestVersion = Some(Version(30, 0, 0)), bobbyRuleViolations = Nil),
              Dependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(20, 2, 0), latestVersion = Some(Version(40, 0, 0)), bobbyRuleViolations = Nil)
            )
          , otherDependencies = Seq(
              Dependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(0, 13, 2), latestVersion = Some(Version(100, 10, 1)), bobbyRuleViolations = Nil)
            )
          , timeForTest
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
    val mockDependencyVersionRepository             = mock[DependencyVersionRepository]
    val mockTeamsAndRepositoriesConnector           = mock[TeamsAndRepositoriesConnector]
    val mockArtifactoryConnector                    = mock[ArtifactoryConnector]
    val mockGithub                                  = mock[Github]

    val dependencyUpdatingService = new DependencyDataUpdatingService(
        mockCuratedDependencyConfigProvider
      , mockRepositoryLibraryDependenciesRepository
      , mockDependencyVersionRepository
      , mockTeamsAndRepositoriesConnector
      , mockArtifactoryConnector
      , mockGithub
      ) {
        override def now: Instant = timeForTest
      }
  }
}
