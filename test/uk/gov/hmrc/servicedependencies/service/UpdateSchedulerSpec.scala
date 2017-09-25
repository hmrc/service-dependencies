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

import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers}
import org.scalatestplus.play.OneAppPerTest
import play.libs.Akka

import scala.concurrent.duration._

class UpdateSchedulerSpec extends FunSpec
  with MockitoSugar
  with Matchers
  with OneAppPerTest with BeforeAndAfterEach {

  trait Counter {
    var count = 0
    def getCallCount: Int = count
    def resetCallCount: Unit = count = 0
  }


  def schedulerF(dependencyDataUpdatingService: DependencyDataUpdatingService) = new UpdateScheduler(Akka.system(), dependencyDataUpdatingService)

  describe("Scheduler") {
    it("should schedule startUpdatingLibraryData based on configured interval") {
      val dependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val scheduler = schedulerF(dependencyDataUpdatingService)
      scheduler.startUpdatingLibraryData(100 milliseconds)
      Thread.sleep(1000)

      verify(dependencyDataUpdatingService, Mockito.atLeast(8)).reloadLatestLibraryVersions
      verify(dependencyDataUpdatingService, Mockito.atMost(11)).reloadLatestLibraryVersions
    }

    it("should schedule reloadLibraryDependencyDataForAllRepositories based on configured interval") {
      val dependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val scheduler = schedulerF(dependencyDataUpdatingService)
      scheduler.startUpdatingLibraryDependencyData(100 milliseconds)
      Thread.sleep(1000)

      verify(dependencyDataUpdatingService, Mockito.atLeast(8)).reloadCurrentDependenciesDataForAllRepositories
      verify(dependencyDataUpdatingService, Mockito.atMost(11)).reloadCurrentDependenciesDataForAllRepositories

    }

    it("should schedule reloadSbtPluginVersionData For AllRepositories based on configured interval") {
      val dependencyDataUpdatingService = mock[DependencyDataUpdatingService]
      val scheduler = schedulerF(dependencyDataUpdatingService)
      scheduler.startUpdatingSbtPluingVersionData(100 milliseconds)
      Thread.sleep(1000)

      verify(dependencyDataUpdatingService, Mockito.atLeast(8)).reloadLatestSbtPluginVersions
      verify(dependencyDataUpdatingService, Mockito.atMost(11)).reloadLatestSbtPluginVersions
    }
  }
}
