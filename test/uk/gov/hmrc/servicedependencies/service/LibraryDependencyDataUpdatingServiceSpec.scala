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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers, OptionValues}
import org.scalatestplus.play.OneAppPerSuite
import reactivemongo.api.DB
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.mongo.Awaiting
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, Other}
import uk.gov.hmrc.servicedependencies.config.{ServiceDependenciesConfig}
import uk.gov.hmrc.servicedependencies.{LibraryDependencyState, RepositoryDependencies}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.presistence.{LibraryVersionRepository, MongoLock, RepositoryLibraryDependenciesRepository}

import scala.concurrent.{ExecutionContext, Future}

class LibraryDependencyDataUpdatingServiceSpec extends FunSpec with MockitoSugar with Matchers with OneAppPerSuite with BeforeAndAfterEach with Awaiting with OptionValues {

  val staticTimeStampGenerator: () => Long = () => 11000l


  describe("reloadLibraryVersions") {


    it("should call the library update function on the repository") {

      val underTest = new TestLibraryDependencyDataUpdatingService(noLockTestMongoLockBuilder)
      when(underTest.dependenciesDataSource.getLatestLibrariesVersions(any()))
        .thenReturn(Seq(LibraryVersion("libYY", Some(Version(1, 1, 1)))))
      underTest.reloadLibraryVersions(staticTimeStampGenerator)

      verify(underTest.mockedLibraryVersionRepository, times(1)).update(MongoLibraryVersion("libYY", Some(Version(1, 1, 1)), staticTimeStampGenerator()))
      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)
    }


    it("should not call the library update function if the mongo is locked ") {
      val underTest = new TestLibraryDependencyDataUpdatingService(denyingTestMongoLockBuilder)

      a[RuntimeException] should be thrownBy underTest.reloadLibraryVersions(staticTimeStampGenerator)

      verifyZeroInteractions(underTest.mockedLibraryVersionRepository)
      verifyZeroInteractions(underTest.mockedDependenciesDataSource)
      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)
    }

  }

  describe("reloadLibraryDependencyDataForAllRepositories") {

    it("should not call the dependency update function if the mongo is locked") {
      val underTest = new TestLibraryDependencyDataUpdatingService(denyingTestMongoLockBuilder)

      a[RuntimeException] should be thrownBy underTest.reloadLibraryDependencyDataForAllRepositories(staticTimeStampGenerator)

      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)

      verifyZeroInteractions(underTest.mockedLibraryVersionRepository)
    }


  }

  describe("getDependencyVersionsForRepository") {
    it("should return the current and latest dependency versions for a repository") {
      val underTest = new TestLibraryDependencyDataUpdatingService(noLockTestMongoLockBuilder)

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


      val maybeDependencies = await(underTest.getDependencyVersionsForRepository(repositoryName))

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe RepositoryDependencies(repositoryName,
        Seq(
          LibraryDependencyState("lib1", Version(1, 0, 0), Some(Version(1, 1, 0))),
          LibraryDependencyState("lib2", Version(2, 0, 0), Some(Version(2, 1, 0)))
        )
      )

    }

    it("test for none") {
      val underTest = new TestLibraryDependencyDataUpdatingService(noLockTestMongoLockBuilder)

      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(None))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))


      val maybeDependencies = await(underTest.getDependencyVersionsForRepository(repositoryName))

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies shouldBe None

    }

    it("test for non existing latest") {
      val underTest = new TestLibraryDependencyDataUpdatingService(noLockTestMongoLockBuilder)

      val libraryDependencies = Seq(
        LibraryDependency("lib1", Version(1, 0, 0)),
        LibraryDependency("lib2", Version(2, 0, 0))
      )
      val repositoryName = "repoXYZ"

      when(underTest.repositoryLibraryDependenciesRepository.getForRepository(any()))
        .thenReturn(Future.successful(Some(MongoRepositoryDependencies(repositoryName, libraryDependencies, Nil))))

      when(underTest.libraryVersionRepository.getAllEntries)
        .thenReturn(Future.successful(Nil))


      val maybeDependencies = await(underTest.getDependencyVersionsForRepository(repositoryName))

      verify(underTest.repositoryLibraryDependenciesRepository, times(1)).getForRepository(repositoryName)

      maybeDependencies.value shouldBe RepositoryDependencies(repositoryName,
        Seq(
          LibraryDependencyState("lib1", Version(1, 0, 0), None),
          LibraryDependencyState("lib2", Version(2, 0, 0), None)
        )
      )

    }
    
  }


  def noLockTestMongoLockBuilder(lockId: String) = new MongoLock(() => mock[DB], lockId) {
    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      body.map(Some(_))
  }

  class TestLibraryDependencyDataUpdatingService(testMongoLockBuilder: String => MongoLock)
    extends DefaultLibraryDependencyDataUpdatingService(mock[ServiceDependenciesConfig]) {

    override val libraryMongoLock = testMongoLockBuilder("libraryMongoLock")
    override val repositoryDependencyMongoLock = testMongoLockBuilder("repositoryDependencyMongoLock")


    override lazy val curatedDependencyConfig = CuratedDependencyConfig(Nil, List("LibX1", "LibX2"), Other(""))

    override lazy val repositoryLibraryDependenciesRepository = mockedRepositoryLibraryDependenciesRepository
    override lazy val libraryVersionRepository = mockedLibraryVersionRepository
    override lazy val teamsAndRepositoriesClient = mockedTeamsAndReposClient
    override lazy val dependenciesDataSource = mockedDependenciesDataSource

    val mockedTeamsAndReposClient = mock[TeamsAndRepositoriesClient]
    val mockedDependenciesDataSource = mock[DependenciesDataSource]
    val mockedLibraryVersionRepository = mock[LibraryVersionRepository]
    val mockedRepositoryLibraryDependenciesRepository = mock[RepositoryLibraryDependenciesRepository]

    when(mockedTeamsAndReposClient.getAllRepositories()).thenReturn(Future.successful(Seq("repo1xx")))

    when(mockedLibraryVersionRepository.update(any())).thenReturn(Future.successful(mock[MongoLibraryVersion]))
  }

  def denyingTestMongoLockBuilder(lockId: String) = new MongoLock(() => mock[DB], lockId) {
    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      throw new RuntimeException(s"Mongo is locked for testing")
  }


}
