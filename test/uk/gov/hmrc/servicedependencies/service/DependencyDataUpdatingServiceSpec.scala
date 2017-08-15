/*
 * Copyright 2017 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers, OptionValues}
import org.scalatestplus.play.OneAppPerSuite
import reactivemongo.api.DB
import uk.gov.hmrc.mongo.Awaiting
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, Other, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.{LibraryDependencyState, RepositoryDependencies, SbtPluginDependencyState}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.presistence.{LibraryVersionRepository, MongoLock, RepositoryLibraryDependenciesRepository, SbtPluginVersionRepository}

import scala.concurrent.{ExecutionContext, Future}

class DependencyDataUpdatingServiceSpec extends FunSpec with MockitoSugar with Matchers with OneAppPerSuite with BeforeAndAfterEach with Awaiting with OptionValues with ScalaFutures with IntegrationPatience{

  val staticTimeStampGenerator: () => Long = () => 11000l


  val curatedDependencyConfig = CuratedDependencyConfig(
    sbtPlugins = Nil,
    libraries = Nil,
    other = None
  )

  describe("reloadLibraryVersions") {

    it("should call the library update function on the repository") {

      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)
      when(underTest.dependenciesDataSource.getLatestLibrariesVersions(any()))
        .thenReturn(Seq(LibraryVersion("libYY", Some(Version(1, 1, 1)))))
      when(underTest.mockedLibraryVersionRepository.update(any())).thenReturn(Future.successful(mock[MongoLibraryVersion]))

      underTest.reloadLibraryVersions(staticTimeStampGenerator).futureValue

      verify(underTest.mockedLibraryVersionRepository, times(1)).update(MongoLibraryVersion("libYY", Some(Version(1, 1, 1)), staticTimeStampGenerator()))
      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)
    }


    it("should not call the library update function if mongo is locked ") {
      val underTest = new TestDependencyDataUpdatingService(denyingTestMongoLockBuilder, curatedDependencyConfig)

      a[RuntimeException] should be thrownBy underTest.reloadLibraryVersions(staticTimeStampGenerator)

      verifyZeroInteractions(underTest.mockedLibraryVersionRepository)
      verifyZeroInteractions(underTest.mockedDependenciesDataSource)
      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)
    }

  }

  describe("reloadSbtPluginVersions") {

    it("should call the sbt plugin update function on the repository") {

      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)
      when(underTest.dependenciesDataSource.getLatestSbtPluginVersions(any()))
        .thenReturn(Seq(SbtPluginVersion("sbtPlugin123", Some(Version(1, 1, 1)))))
      when(underTest.mockedSbtPluginVersionRepository.update(any())).thenReturn(Future.successful(mock[MongoSbtPluginVersion]))

      underTest.reloadSbtPluginVersions(staticTimeStampGenerator).futureValue

      verify(underTest.mockedSbtPluginVersionRepository, times(1)).update(MongoSbtPluginVersion("sbtPlugin123", Some(Version(1, 1, 1)), staticTimeStampGenerator()))
      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)
      verifyZeroInteractions(underTest.mockedLibraryVersionRepository)
    }


    it("should not call the sbt plugin update function if mongo is locked ") {
      val underTest = new TestDependencyDataUpdatingService(denyingTestMongoLockBuilder, curatedDependencyConfig)

      a[RuntimeException] should be thrownBy underTest.reloadSbtPluginVersions(staticTimeStampGenerator)

      verifyZeroInteractions(underTest.mockedSbtPluginVersionRepository)
      verifyZeroInteractions(underTest.mockedLibraryVersionRepository)
      verifyZeroInteractions(underTest.mockedDependenciesDataSource)
      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)
    }

  }

  describe("reloadLibraryDependencyDataForAllRepositories") {

    it("should call the dependency update function to persist the dependencies") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val mongoRepositoryDependencies = Seq(MongoRepositoryDependencies("repoXyz", Nil, Nil))

      when(underTest.mockedRepositoryLibraryDependenciesRepository.getAllEntries).thenReturn(Future.successful(mongoRepositoryDependencies))
      when(underTest.dependenciesDataSource.persistDependenciesForAllRepositories(any(), any(), any(), any())).thenReturn(Future.successful(mongoRepositoryDependencies))

      underTest.reloadDependenciesDataForAllRepositories(staticTimeStampGenerator).futureValue shouldBe mongoRepositoryDependencies

      //!@ TODO: how do we verify the persister function being called (last param)?
      verify(underTest.dependenciesDataSource, times(1)).persistDependenciesForAllRepositories(eqTo(underTest.curatedDependencyConfig), any(), eqTo(mongoRepositoryDependencies), any())
    }

    it("should not call the dependency update function if the mongo is locked") {
      val underTest = new TestDependencyDataUpdatingService(denyingTestMongoLockBuilder, curatedDependencyConfig)

      a[RuntimeException] should be thrownBy underTest.reloadDependenciesDataForAllRepositories(staticTimeStampGenerator)

      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)

      verifyZeroInteractions(underTest.mockedLibraryVersionRepository)
    }


  }

  describe("getDependencyVersionsForRepository") {
    it("should return the current and latest library dependency versions for a repository") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val libraryDependencies = Seq(
        LibraryDependency("lib1", Version(1, 0, 0)),
        LibraryDependency("lib2", Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(Some(MongoRepositoryDependencies(repositoryName, libraryDependencies, Nil))))

      val referenceLibraryVersions = Seq(
        MongoLibraryVersion("lib1", Some(Version(1, 1, 0))),
        MongoLibraryVersion("lib2", Some(Version(2, 1, 0)))
      )
      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceLibraryVersions))


      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))


      val maybeDependencies = await(underTest.getDependencyVersionsForRepository(repositoryName))

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe RepositoryDependencies(repositoryName,
        Seq(
          LibraryDependencyState("lib1", Version(1, 0, 0), Some(Version(1, 1, 0))),
          LibraryDependencyState("lib2", Version(2, 0, 0), Some(Version(2, 1, 0)))
        ), Nil
      )

    }

    it("should return the current and latest sbt plugin dependency versions for a repository") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val sbtPluginDependencies = Seq(
        SbtPluginDependency("plugin1", Version(1, 0, 0)),
        SbtPluginDependency("plugin2", Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(Some(MongoRepositoryDependencies(repositoryName, Nil, sbtPluginDependencies))))

      val referenceSbtPluginVersions = Seq(
        MongoSbtPluginVersion("plugin1", Some(Version(3, 1, 0))),
        MongoSbtPluginVersion("plugin2", Some(Version(4, 1, 0)))
      )

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceSbtPluginVersions))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies = await(underTest.getDependencyVersionsForRepository(repositoryName))

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe RepositoryDependencies(repositoryName,
        Nil,
        Seq(
          SbtPluginDependencyState("plugin1", Version(1, 0, 0), Some(Version(3, 1, 0)), false),
          SbtPluginDependencyState("plugin2", Version(2, 0, 0), Some(Version(4, 1, 0)), false)
        )
      )

    }

    it("should return the current and latest external sbt plugin dependency versions for a repository") {
      val curatedDependencyConfig = CuratedDependencyConfig(
        sbtPlugins = List(SbtPluginConfig("org.com", "internal-plugin", None), SbtPluginConfig("org.com", "external-plugin", Some(Version(11, 22, 33)))),
        libraries = Nil,
        other = None
      )

      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val sbtPluginDependencies = Seq(
        SbtPluginDependency("internal-plugin", Version(1, 0, 0)),
        SbtPluginDependency("external-plugin", Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(Some(MongoRepositoryDependencies(repositoryName, Nil, sbtPluginDependencies))))

      val referenceSbtPluginVersions = Seq(
        MongoSbtPluginVersion("internal-plugin", Some(Version(3, 1, 0))),
        MongoSbtPluginVersion("external-plugin", None)
      )

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(referenceSbtPluginVersions))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      val maybeDependencies = await(underTest.getDependencyVersionsForRepository(repositoryName))

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe RepositoryDependencies(repositoryName,
        Nil,
        Seq(
          SbtPluginDependencyState("internal-plugin", Version(1, 0, 0), Some(Version(3, 1, 0)), false),
          SbtPluginDependencyState("external-plugin", Version(2, 0, 0), Some(Version(11, 22, 33)), true)
        )
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


      val maybeDependencies = await(underTest.getDependencyVersionsForRepository(repositoryName))

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies shouldBe None                             

    }

    it("test for non existing latest") {
      val underTest = new TestDependencyDataUpdatingService(noLockTestMongoLockBuilder, curatedDependencyConfig)

      val libraryDependencies = Seq(
        LibraryDependency("lib1", Version(1, 0, 0)),
        LibraryDependency("lib2", Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(Some(MongoRepositoryDependencies(repositoryName, libraryDependencies, Nil))))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))

      when(underTest.sbtPluginVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))



      val maybeDependencies = await(underTest.getDependencyVersionsForRepository(repositoryName))

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe RepositoryDependencies(repositoryName,
        Seq(
          LibraryDependencyState("lib1", Version(1, 0, 0), None),
          LibraryDependencyState("lib2", Version(2, 0, 0), None)
        ), Nil
      )

    }

  }


  def noLockTestMongoLockBuilder(lockId: String) = new MongoLock(() => mock[DB], lockId) {
    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      body.map(Some(_))
  }

  class TestDependencyDataUpdatingService(testMongoLockBuilder: (String) => MongoLock, dependencyConfig: CuratedDependencyConfig)
    extends DefaultDependencyDataUpdatingService(mock[ServiceDependenciesConfig]) {

    override val libraryMongoLock = testMongoLockBuilder("libraryMongoLock")
    override val sbtPluginMongoLock = testMongoLockBuilder("sbtPluginMongoLock")
    override val repositoryDependencyMongoLock = testMongoLockBuilder("repositoryDependencyMongoLock")


    override lazy val curatedDependencyConfig = dependencyConfig


    override lazy val repositoryLibraryDependenciesRepository = mockedRepositoryLibraryDependenciesRepository
    override lazy val libraryVersionRepository = mockedLibraryVersionRepository
    override lazy val sbtPluginVersionRepository = mockedSbtPluginVersionRepository

    override lazy val teamsAndRepositoriesClient = mockedTeamsAndReposClient
    override lazy val dependenciesDataSource = mockedDependenciesDataSource

    val mockedTeamsAndReposClient = mock[TeamsAndRepositoriesClient]
    val mockedDependenciesDataSource = mock[DependenciesDataSource]
    val mockedLibraryVersionRepository = mock[LibraryVersionRepository]
    val mockedSbtPluginVersionRepository = mock[SbtPluginVersionRepository]
    val mockedRepositoryLibraryDependenciesRepository = mock[RepositoryLibraryDependenciesRepository]

    when(mockedTeamsAndReposClient.getAllRepositories()).thenReturn(Future.successful(Seq("repo1xx")))

  }

  def denyingTestMongoLockBuilder(lockId: String) = new MongoLock(() => mock[DB], lockId) {
    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      throw new RuntimeException(s"Mongo is locked for testing")
  }


}
