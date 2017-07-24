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
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers}
import org.scalatestplus.play.OneAppPerSuite
import reactivemongo.api.DB
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.mongo.Awaiting
import uk.gov.hmrc.servicedependencies.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.model.{LibraryVersion, MongoLibraryVersion, RepositoryLibraryDependencies, Version}
import uk.gov.hmrc.servicedependencies.presistence.{LibraryVersionRepository, MongoLock, RepositoryLibraryDependenciesRepository}

import scala.concurrent.{ExecutionContext, Future}

class LibraryDependencyDataUpdatingServiceSpec extends FunSpec with MockitoSugar with Matchers with OneAppPerSuite with BeforeAndAfterEach with Awaiting {

  val staticTimeStampGenerator: () => Long = () => 11000l


  describe("reloadLibraryVersions") {


    it("should call the library update function on the repository") {

      val underTest = new TestLibraryDependencyDataUpdatingService(noLockTestMongoLockBuilder)
      when(underTest.dependenciesDataSource.getLatestLibrariesVersions(any()))
        .thenReturn(Seq(LibraryVersion("libYY", Version(1, 1, 1))))
      underTest.reloadLibraryVersions(staticTimeStampGenerator)

      verify(underTest.mockedLibraryVersionRepository, times(1)).update(MongoLibraryVersion("libYY", Version(1, 1, 1), staticTimeStampGenerator()))
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


    //!@TODO do we need this test now that the persisting happens on the inside?
//    it("should call the dependency update function") {
//      val underTest = new TestLibraryDependencyDataUpdatingService(noLockTestMongoLockBuilder)
//
//      when(underTest.mockedDependenciesDataSource.getDependenciesForAllRepositories(any(), any()))
//        .thenReturn(Future.successful(Seq(RepositoryLibraryDependencies("repo123", Nil, staticTimeStampGenerator()))))
//
//      when(underTest.mockedRepositoryLibraryDependenciesRepository.update(any())).thenReturn(Future.successful(RepositoryLibraryDependencies("repo123", Nil, staticTimeStampGenerator())))
//
//      await(underTest.reloadLibraryDependencyDataForAllRepositories(staticTimeStampGenerator))
//
//      verify(underTest.mockedRepositoryLibraryDependenciesRepository, times(1)).update(RepositoryLibraryDependencies("repo123", Nil, staticTimeStampGenerator()))
//
//      verifyZeroInteractions(underTest.mockedLibraryVersionRepository)
//    }

    it("should not call the dependency update function if the mongo is locked") {
      val underTest = new TestLibraryDependencyDataUpdatingService(denyingTestMongoLockBuilder)

      a[RuntimeException] should be thrownBy underTest.reloadLibraryDependencyDataForAllRepositories(staticTimeStampGenerator)

      verifyZeroInteractions(underTest.mockedRepositoryLibraryDependenciesRepository)

      verifyZeroInteractions(underTest.mockedLibraryVersionRepository)
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

    override lazy val curatedLibraries: List[String] = List("LibX1", "LibX2")

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
