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
import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, MongoLockService}
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.servicedependencies.config.CuratedDependencyConfigProvider
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class DependencyDataUpdatingServiceSpec
  extends AnyFunSpec
     with MockitoSugar
     with Matchers
     with GuiceOneAppPerTest
     with OptionValues
     with MongoSupport {

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  private val timeForTest = Instant.now()

  private val curatedDependencyConfig = CuratedDependencyConfig(
    sbtPlugins        = Nil,
    libraries         = Nil,
    otherDependencies = Nil
  )

  describe("reloadLibraryVersions") {

    it("should call the library update function on the repository") {

      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)
      when(underTest.dependenciesDataSource.getLatestLibrariesVersions(any()))
        .thenReturn(Seq(LibraryVersion(name = "libYY", group = "uk.gov.hmrc", version = Some(Version(1, 1, 1)))))
      when(underTest.libraryVersionRepository.update(any())).thenReturn(Future.successful(mock[MongoLibraryVersion]))

      underTest.testDependencyUpdatingService.reloadLatestLibraryVersions().futureValue

      verify(underTest.libraryVersionRepository, times(1))
        .update(MongoLibraryVersion(name = "libYY", group = "uk.gov.hmrc", version = Some(Version(1, 1, 1)), updateDate = timeForTest))
      verifyZeroInteractions(underTest.repositoryLibraryDependenciesRepository)
    }

    it("should not call the library update function if mongo is locked ") {
      val underTest = new TestDependencyDataUpdatingService(denyingTestMongoLockBuilder, curatedDependencyConfig)

      a[RuntimeException] should be thrownBy underTest.testDependencyUpdatingService.reloadLatestLibraryVersions()

      verifyZeroInteractions(underTest.libraryVersionRepository)
      verifyZeroInteractions(underTest.dependenciesDataSource)
      verifyZeroInteractions(underTest.repositoryLibraryDependenciesRepository)
    }
  }

  describe("reloadSbtPluginVersions") {

    it("should call the sbt plugin update function on the repository") {

      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)
      when(underTest.dependenciesDataSource.getLatestSbtPluginVersions(any()))
        .thenReturn(Seq(SbtPluginVersion(name = "sbtPlugin123", group = "uk.gov.hmrc", version = Some(Version(1, 1, 1)))))
      when(underTest.sbtPluginVersionRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoSbtPluginVersion]))

      underTest.testDependencyUpdatingService.reloadLatestSbtPluginVersions().futureValue

      verify(underTest.sbtPluginVersionRepository, times(1))
        .update(MongoSbtPluginVersion(name = "sbtPlugin123", group = "uk.gov.hmrc", version = Some(Version(1, 1, 1)), updateDate = timeForTest))
      verifyZeroInteractions(underTest.repositoryLibraryDependenciesRepository)
      verifyZeroInteractions(underTest.libraryVersionRepository)
    }

    it("should not call the sbt plugin update function if mongo is locked ") {
      val underTest = new TestDependencyDataUpdatingService(denyingTestMongoLockBuilder, curatedDependencyConfig)

      a[RuntimeException] should be thrownBy underTest.testDependencyUpdatingService.reloadLatestSbtPluginVersions()

      verifyZeroInteractions(underTest.sbtPluginVersionRepository)
      verifyZeroInteractions(underTest.libraryVersionRepository)
      verifyZeroInteractions(underTest.dependenciesDataSource)
      verifyZeroInteractions(underTest.repositoryLibraryDependenciesRepository)
    }
  }

  describe("reloadMongoRepositoryDependencyDataForAllRepositories") {

    it("should call the dependency update function to persist the dependencies") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val mongoRepositoryDependencies = Seq(MongoRepositoryDependencies("repoXyz", Nil, Nil, Nil, timeForTest))

      when(underTest.repositoryLibraryDependenciesRepository.getAllEntries)
        .thenReturn(Future.successful(mongoRepositoryDependencies))
      when(underTest.dependenciesDataSource.persistDependenciesForAllRepositories(any(), any(), any())(any()))
        .thenReturn(Future.successful(mongoRepositoryDependencies))

      underTest.testDependencyUpdatingService
        .reloadCurrentDependenciesDataForAllRepositories()(HeaderCarrier())
        .futureValue shouldBe mongoRepositoryDependencies

      //!@ TODO: how do we verify the persister function being called (last param)?
      verify(underTest.dependenciesDataSource, times(1)).persistDependenciesForAllRepositories(
        eqTo(underTest.testDependencyUpdatingService.curatedDependencyConfig),
        eqTo(mongoRepositoryDependencies),
        eqTo(false))(any())
    }

    it("should force the persistence of updates if the 'force' flag is true") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val mongoRepositoryDependencies = Seq(MongoRepositoryDependencies("repoXyz", Nil, Nil, Nil, timeForTest))

      when(underTest.repositoryLibraryDependenciesRepository.getAllEntries)
        .thenReturn(Future.successful(mongoRepositoryDependencies))
      when(underTest.dependenciesDataSource.persistDependenciesForAllRepositories(any(), any(), any())(any()))
        .thenReturn(Future.successful(mongoRepositoryDependencies))

      underTest.testDependencyUpdatingService
        .reloadCurrentDependenciesDataForAllRepositories(force = true)(HeaderCarrier())
        .futureValue shouldBe mongoRepositoryDependencies

      //!@ TODO: how do we verify the persister function being called (last param)?
      verify(underTest.dependenciesDataSource, times(1)).persistDependenciesForAllRepositories(
        eqTo(underTest.testDependencyUpdatingService.curatedDependencyConfig),
        eqTo(mongoRepositoryDependencies),
        eqTo(true))(any())
    }

    it("should not call the dependency update function if the mongo is locked") {
      val underTest = new TestDependencyDataUpdatingService(denyingTestMongoLockBuilder, curatedDependencyConfig)

      a[RuntimeException] should be thrownBy underTest.testDependencyUpdatingService
        .reloadCurrentDependenciesDataForAllRepositories()(HeaderCarrier())

      verifyZeroInteractions(underTest.repositoryLibraryDependenciesRepository)

      verifyZeroInteractions(underTest.libraryVersionRepository)
    }
  }

  describe("getDependencyVersionsForRepository") {
    it("should return the current and latest library dependency versions for a repository") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val libraryDependencies = Seq(
        MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0)),
        MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(repositoryName, libraryDependencies, Nil, Nil, timeForTest))))

      val referenceLibraryVersions = Seq(
        MongoLibraryVersion(name = "lib1", group = "uk.gov.hmrc", version = Some(Version(1, 1, 0))),
        MongoLibraryVersion(name = "lib2", group = "uk.gov.hmrc", version = Some(Version(2, 1, 0)))
      )
      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceLibraryVersions))

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        underTest.testDependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

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
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val sbtPluginDependencies = Seq(
        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0)),
        MongoRepositoryDependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(repositoryName, Nil, sbtPluginDependencies, Nil, timeForTest))))

      val referenceSbtPluginVersions = Seq(
        MongoSbtPluginVersion(name = "plugin1", group = "uk.gov.hmrc", version = Some(Version(3, 1, 0))),
        MongoSbtPluginVersion(name = "plugin2", group = "uk.gov.hmrc", version = Some(Version(4, 1, 0)))
      )

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceSbtPluginVersions))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        underTest.testDependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

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
      val curatedDependencyConfig = CuratedDependencyConfig(
        sbtPlugins = List(
          SbtPluginConfig(name = "internal-plugin", group = "uk.gov.hmrc", latestVersion = None),
          SbtPluginConfig(name = "external-plugin", group = "uk.edu"     , latestVersion = Some(Version(11, 22, 33)))),
        libraries         = Nil,
        otherDependencies = Nil
      )

      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val sbtPluginDependencies = Seq(
        MongoRepositoryDependency(name = "internal-plugin", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0)),
        MongoRepositoryDependency(name = "external-plugin", group = "uk.edu"     , currentVersion = Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(repositoryName, Nil, sbtPluginDependencies, Nil, timeForTest))))

      val referenceSbtPluginVersions = Seq(
        MongoSbtPluginVersion(name = "internal-plugin", group = "uk.gov.hmrc", version = Some(Version(3, 1, 0))),
        MongoSbtPluginVersion(name = "external-plugin", group = "uk.edu"     , version = None)
      )

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceSbtPluginVersions))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        underTest.testDependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe Dependencies(
        repositoryName         = repositoryName,
        libraryDependencies    = Nil,
        sbtPluginsDependencies = Seq(
          Dependency(name = "internal-plugin", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0), latestVersion = Some(Version(3, 1, 0))   , bobbyRuleViolations = List.empty),
          Dependency(name = "external-plugin", group = "uk.edu"     , currentVersion = Version(2, 0, 0), latestVersion = Some(Version(11, 22, 33)), bobbyRuleViolations = List.empty)
        ),
        otherDependencies = Nil,
        timeForTest
      )
    }

    it("test for none") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(None))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        underTest.testDependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies shouldBe None
    }

    it("test for non existing latest") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val libraryDependencies = Seq(
        MongoRepositoryDependency(name = "lib1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 0)),
        MongoRepositoryDependency(name = "lib2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(
          Some(MongoRepositoryDependencies(repositoryName, libraryDependencies, Nil, Nil, timeForTest))))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies =
        underTest.testDependencyUpdatingService.getDependencyVersionsForRepository(repositoryName).futureValue

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

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
      val underTest = new TestDependencyDataUpdatingService(
        noLockTestMongoLockBuilder,
        curatedDependencyConfig.copy(otherDependencies = Seq(OtherDependencyConfig(name = "sbt", group = "org.scala-sbt", latestVersion = Some(Version(100, 10, 1))))))

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
        MongoLibraryVersion(name = "lib1", group = "uk.gov.hmrc", version = Some(Version(3, 0, 0))),
        MongoLibraryVersion(name = "lib2", group = "uk.gov.hmrc", version = Some(Version(4, 0, 0)))
      )

      val referenceSbtPluginVersions = Seq(
        MongoSbtPluginVersion(name = "plugin1", group = "uk.gov.hmrc", version = Some(Version(30, 0, 0))),
        MongoSbtPluginVersion(name = "plugin2", group = "uk.gov.hmrc", version = Some(Version(40, 0, 0)))
      )

      val repository1 = "repo1"
      val repository2 = "repo2"

      when(underTest.repositoryLibraryDependenciesRepository.getAllEntries)
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

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceLibraryVersions))

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceSbtPluginVersions))

      val maybeDependencies = underTest.testDependencyUpdatingService.getDependencyVersionsForAllRepositories().futureValue

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getAllEntries

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

  class TestDependencyDataUpdatingService(
    testMongoLockBuilder: (String) => MongoLockService,
    dependencyConfig: CuratedDependencyConfig) {

    val curatedDependencyConfigProvider: CuratedDependencyConfigProvider = mock[CuratedDependencyConfigProvider]
    when(curatedDependencyConfigProvider.curatedDependencyConfig).thenReturn(dependencyConfig)

    val repositoryLibraryDependenciesRepository: RepositoryLibraryDependenciesRepository =
      mock[RepositoryLibraryDependenciesRepository]

    val libraryVersionRepository: LibraryVersionRepository = mock[LibraryVersionRepository]

    val sbtPluginVersionRepository: SbtPluginVersionRepository = mock[SbtPluginVersionRepository]

    val locksRepository: LocksRepository = mock[LocksRepository]

    val mongoLocks: MongoLocks = mock[MongoLocks]

    val dependenciesDataSource: DependenciesDataSource = mock[DependenciesDataSource]

    val testDependencyUpdatingService = new DependencyDataUpdatingService(
      curatedDependencyConfigProvider,
      repositoryLibraryDependenciesRepository,
      libraryVersionRepository,
      sbtPluginVersionRepository,
      locksRepository,
      mongoLocks,
      dependenciesDataSource
    ) {
      override def now: Instant = timeForTest

      override val libraryMongoLock              = testMongoLockBuilder("libraryMongoLock")
      override val sbtPluginMongoLock            = testMongoLockBuilder("sbtPluginMongoLock")
      override val repositoryDependencyMongoLock = testMongoLockBuilder("repositoryDependencyMongoLock")
    }
  }

  def noLockTestMongoLockBuilder(testLockId: String): MongoLockService = new MongoLockService {
    override val lockRepository: MongoLockRepository = new MongoLockRepository(mongoComponent, new CurrentTimestampSupport())
    override val lockId: String = testLockId
    override val ttl: Duration = 1.hour
    override def attemptLockWithRelease[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      body.map(Some(_))
  }

  def denyingTestMongoLockBuilder(testLockId: String): MongoLockService = new MongoLockService {
    override val lockRepository: MongoLockRepository = new MongoLockRepository(mongoComponent, new CurrentTimestampSupport())
    override val lockId: String = testLockId
    override val ttl: Duration = 1.hour
    override def attemptLockWithRelease[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      throw new RuntimeException(s"Mongo is locked for testing")
  }
}
