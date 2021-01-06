/*
 * Copyright 2021 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.model.MongoRepositoryDependencies
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, LocksRepository, RepositoryDependenciesRepository}
import uk.gov.hmrc.servicedependencies.service.DependencyDataUpdatingService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AdministrationControllerSpec
    extends AnyFreeSpec
    with BeforeAndAfterEach
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with IntegrationPatience
    with OptionValues {

  "reloadLibraryDependenciesForAllRepositories" - {
    "should call the reloadLibraryDependencyDataForAllRepositories on the service" in {
      val boot = Boot.init

      when(boot.mockDependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories(any()))
        .thenReturn(Future.successful(Seq.empty[MongoRepositoryDependencies]))

      boot.controller.reloadLibraryDependenciesForAllRepositories().apply(FakeRequest())

      verify(boot.mockDependencyDataUpdatingService)
        .reloadCurrentDependenciesDataForAllRepositories(any())
    }
  }

  case class Boot(
    mockDependencyDataUpdatingService: DependencyDataUpdatingService
  , controller                       : AdministrationController
  )

  object Boot {
    def init: Boot = {
      val mockDependencyDataUpdatingService    = mock[DependencyDataUpdatingService]
      val mockLocksRepository                  = mock[LocksRepository]
      val mockRepositoryDependenciesRepository = mock[RepositoryDependenciesRepository]
      val mockLatestVersionRepository          = mock[LatestVersionRepository]

      val controller = new AdministrationController(
          mockDependencyDataUpdatingService
        , mockLocksRepository
        , mockRepositoryDependenciesRepository
        , mockLatestVersionRepository
        , stubControllerComponents()
        )

      Boot(
          mockDependencyDataUpdatingService
        , controller
        )
    }
  }
}