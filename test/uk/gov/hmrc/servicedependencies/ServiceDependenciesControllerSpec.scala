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

package uk.gov.hmrc.servicedependencies

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Configuration}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, MongoRepositoryDependencies, Version}
import uk.gov.hmrc.servicedependencies.service._
import play.api.inject.bind

import scala.concurrent.Future

class ServiceDependenciesControllerSpec
  extends FreeSpec
    with BeforeAndAfterEach
    with OneServerPerSuite
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with IntegrationPatience
    with OptionValues {

  override implicit lazy val app: Application =
    new GuiceApplicationBuilder()
      .configure("teams-and-repositories" -> "http://something.teams-and-repositories")
      .build()


  "getDependencyVersionsForRepository" - {
    "should get dependency versions for a repository using the service" in new Setup {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val repoName = "repo1"
      val repositoryDependencies = RepositoryDependencies(repoName, Nil, Nil, Nil, None)

      when(mockedLibraryDependencyDataUpdatingService.getDependencyVersionsForRepository(any()))
        .thenReturn(Future.successful(Some(repositoryDependencies)))

      val result = makeServiceDependenciesImpl(mockedLibraryDependencyDataUpdatingService).getDependencyVersionsForRepository(repoName).apply(FakeRequest())
      val maybeRepositoryDependencies = contentAsJson(result).asOpt[RepositoryDependencies]

      maybeRepositoryDependencies.value shouldBe repositoryDependencies
      Mockito.verify(mockedLibraryDependencyDataUpdatingService).getDependencyVersionsForRepository(repoName)
    }

  }

  "reloadLibraryDependenciesForAllRepositories" - {
    "should call the reloadLibraryDependencyDataForAllRepositories on the service" in new Setup {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val repoName = "repo1"


      when(mockedLibraryDependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories())
        .thenReturn(Future.successful(Seq.empty[MongoRepositoryDependencies]))

      val controller = makeServiceDependenciesImpl(mockedLibraryDependencyDataUpdatingService)
      controller.reloadLibraryDependenciesForAllRepositories().apply(FakeRequest())

      Mockito.verify(mockedLibraryDependencyDataUpdatingService).reloadCurrentDependenciesDataForAllRepositories()
    }

  }

  "get libraries" - {
    "should get all the curated libraries using the service" in new Setup {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val libraryVersions = Seq(
        MongoLibraryVersion("lib1", Some(Version(1, 0, 0)), 1234l),
        MongoLibraryVersion("lib2", Some(Version(2, 0, 0)), 1234l),
        MongoLibraryVersion("lib3", Some(Version(3, 0, 0)), 1234l)
      )
      when(mockedLibraryDependencyDataUpdatingService.getAllCuratedLibraries()).thenReturn(Future.successful(
        libraryVersions
      ))

      val result = makeServiceDependenciesImpl(mockedLibraryDependencyDataUpdatingService).libraries().apply(FakeRequest())
      val mongoLibraryVersions = contentAsJson(result).as[Seq[MongoLibraryVersion]]

      mongoLibraryVersions.size shouldBe 3
      mongoLibraryVersions should contain theSameElementsAs libraryVersions
    }

  }

  "get dependencies" - {
    "should get all dependencies using the service" in new Setup {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val libraryDependencies = Seq(
        RepositoryDependencies("repo1", Nil, Nil, Nil, None),
        RepositoryDependencies("repo2", Nil, Nil, Nil, None),
        RepositoryDependencies("repo3", Nil, Nil, Nil, None)
      )
      when(mockedLibraryDependencyDataUpdatingService.getDependencyVersionsForAllRepositories()).thenReturn(Future.successful(
        libraryDependencies
      ))

      val result = makeServiceDependenciesImpl(mockedLibraryDependencyDataUpdatingService).dependencies().apply(FakeRequest())
      val repositoryLibraryDependencies = contentAsJson(result).as[Seq[RepositoryDependencies]]

      repositoryLibraryDependencies.size shouldBe 3
      repositoryLibraryDependencies should contain theSameElementsAs libraryDependencies
    }

  }


  trait Setup {
    def makeServiceDependenciesImpl(libraryDependencyUpdatingService: DependencyDataUpdatingService) =
      new ServiceDependenciesController(Configuration(), libraryDependencyUpdatingService, mock[ServiceDependenciesConfig])
  }

}
