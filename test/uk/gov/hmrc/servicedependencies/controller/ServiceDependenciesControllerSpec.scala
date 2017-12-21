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

package uk.gov.hmrc.servicedependencies.controller

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import play.api.Configuration
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.controller.model.Dependencies
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, MongoRepositoryDependencies, Version}
import uk.gov.hmrc.servicedependencies.service._
import uk.gov.hmrc.time.DateTimeUtils

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
  

  "getDependencyVersionsForRepository" - {
    "should get dependency versions for a repository using the service" in new Setup {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val repoName = "repo1"
      val repositoryDependencies = Dependencies(repoName, Nil, Nil, Nil, DateTimeUtils.now)

      when(mockedLibraryDependencyDataUpdatingService.getDependencyVersionsForRepository(any()))
        .thenReturn(Future.successful(Some(repositoryDependencies)))

      val result = makeServiceDependenciesImpl(mockedLibraryDependencyDataUpdatingService).getDependencyVersionsForRepository(repoName).apply(FakeRequest())
      val maybeRepositoryDependencies = contentAsJson(result).asOpt[Dependencies]

      maybeRepositoryDependencies.value shouldBe repositoryDependencies
      Mockito.verify(mockedLibraryDependencyDataUpdatingService).getDependencyVersionsForRepository(repoName)
    }

  }

  "get dependencies" - {
    "should get all dependencies using the service" in new Setup {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val libraryDependencies = Seq(
        Dependencies("repo1", Nil, Nil, Nil, DateTimeUtils.now),
        Dependencies("repo2", Nil, Nil, Nil, DateTimeUtils.now),
        Dependencies("repo3", Nil, Nil, Nil, DateTimeUtils.now)
      )
      when(mockedLibraryDependencyDataUpdatingService.getDependencyVersionsForAllRepositories()).thenReturn(Future.successful(
        libraryDependencies
      ))

      val result = makeServiceDependenciesImpl(mockedLibraryDependencyDataUpdatingService).dependencies().apply(FakeRequest())
      val repositoryLibraryDependencies = contentAsJson(result).as[Seq[Dependencies]]

      repositoryLibraryDependencies.size shouldBe 3
      repositoryLibraryDependencies should contain theSameElementsAs libraryDependencies
    }

  }


  trait Setup {
    def makeServiceDependenciesImpl(libraryDependencyUpdatingService: DependencyDataUpdatingService) =
      new ServiceDependenciesController(Configuration(), libraryDependencyUpdatingService, mock[ServiceDependenciesConfig])
  }

}
