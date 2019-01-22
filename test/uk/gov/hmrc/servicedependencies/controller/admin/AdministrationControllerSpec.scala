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

package uk.gov.hmrc.servicedependencies.controller.admin

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{verify, when}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FreeSpec, Matchers, OptionValues}
import play.api.test.Helpers._
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.test.FakeRequest
import uk.gov.hmrc.servicedependencies.model.MongoRepositoryDependencies
import uk.gov.hmrc.servicedependencies.service.DependencyDataUpdatingService

import scala.concurrent.Future

class AdministrationControllerSpec
    extends FreeSpec
    with BeforeAndAfterEach
    with GuiceOneServerPerSuite
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with IntegrationPatience
    with OptionValues {

  "reloadLibraryDependenciesForAllRepositories" - {

    "should call the reloadLibraryDependencyDataForAllRepositories on the service" in {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]

      when(mockedLibraryDependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories(any())(any()))
        .thenReturn(Future.successful(Seq.empty[MongoRepositoryDependencies]))

      val controller = new AdministrationController(mockedLibraryDependencyDataUpdatingService, stubControllerComponents())
      controller.reloadLibraryDependenciesForAllRepositories().apply(FakeRequest())

      verify(mockedLibraryDependencyDataUpdatingService).reloadCurrentDependenciesDataForAllRepositories(eqTo(false))(
        any())
    }

    "should accept an optional query parameter to force the dependencies to be reloaded" in {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]

      when(mockedLibraryDependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories(any())(any()))
        .thenReturn(Future.successful(Seq.empty[MongoRepositoryDependencies]))

      val controller = new AdministrationController(mockedLibraryDependencyDataUpdatingService, stubControllerComponents())
      controller.reloadLibraryDependenciesForAllRepositories(Some(true)).apply(FakeRequest())

      verify(mockedLibraryDependencyDataUpdatingService).reloadCurrentDependenciesDataForAllRepositories(eqTo(true))(
        any())
    }

    "should not force dependencies if the force query parameter is set to false" in {
      val mockedLibraryDependencyDataUpdatingService = mock[DependencyDataUpdatingService]

      when(mockedLibraryDependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories(any())(any()))
        .thenReturn(Future.successful(Seq.empty[MongoRepositoryDependencies]))

      val controller = new AdministrationController(mockedLibraryDependencyDataUpdatingService, stubControllerComponents())
      controller.reloadLibraryDependenciesForAllRepositories(Some(false)).apply(FakeRequest())

      verify(mockedLibraryDependencyDataUpdatingService).reloadCurrentDependenciesDataForAllRepositories(eqTo(false))(
        any())
    }
  }
}
