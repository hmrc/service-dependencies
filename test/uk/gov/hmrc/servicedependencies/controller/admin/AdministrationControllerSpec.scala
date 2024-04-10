/*
 * Copyright 2023 HM Revenue & Customs
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

import org.mockito.Mockito.{verify, when}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.model.LatestVersion
import uk.gov.hmrc.servicedependencies.service.LatestVersionService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AdministrationControllerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  "reloadLatestVersions" should {
    "call the reloadLibraryDependencyDataForAllRepositories on the service" in {
      val boot = Boot.init

      when(boot.mockLatestVersionService.reloadLatestVersions())
        .thenReturn(Future.successful(List.empty[LatestVersion]))

      boot.controller.reloadLatestVersions.apply(FakeRequest())

      verify(boot.mockLatestVersionService)
        .reloadLatestVersions()
    }
  }

  case class Boot(
    mockLatestVersionService: LatestVersionService
  , controller              : AdministrationController
  )

  object Boot {
    def init: Boot = {
      val mockLatestVersionService = mock[LatestVersionService]

      val controller = new AdministrationController(
          mockLatestVersionService
        , stubControllerComponents()
        )

      Boot(
          mockLatestVersionService
        , controller
        )
    }
  }
}
