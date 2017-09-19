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

import akka.actor.ActorSystem
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FunSpec, Matchers}
import org.scalatestplus.play.OneAppPerSuite
import play.libs.Akka
import uk.gov.hmrc.servicedependencies.ServiceDependenciesController

import scala.concurrent.duration._

class UpdateSchedulerSpec extends FunSpec
  with MockitoSugar
  with Matchers
  with OneAppPerSuite with BeforeAndAfterEach {


  trait Counter {
    def getCallCount: Int
    def resetCallCount: Unit
  }


  def schedulerF = new UpdateScheduler(Akka.system(), mock[ServiceDependenciesController]) with Counter {

    var count = 0

    override def dependencyDataUpdatingService: DependencyDataUpdatingService = {
      count += 1

      mock[DependencyDataUpdatingService]
    }

    override def getCallCount: Int = count

    override def resetCallCount: Unit = count = 0
  }

  describe("Scheduler") {
    it("should schedule startUpdatingLibraryData based on configured interval") {
      val scheduler = schedulerF
      scheduler.startUpdatingLibraryData(100 milliseconds)
      Thread.sleep(1000)

      scheduler.getCallCount should be > 8
      scheduler.getCallCount should be < 11
    }
    it("should schedule reloadLibraryDependencyDataForAllRepositories based on configured interval") {
      val scheduler = schedulerF
      scheduler.startUpdatingLibraryDependencyData(100 milliseconds)
      Thread.sleep(1000)

      scheduler.getCallCount should be > 8
      scheduler.getCallCount should be < 11
    }

    it("should schedule reloadSbtPluginVersionData For AllRepositories based on configured interval") {
      val scheduler = schedulerF
      scheduler.startUpdatingSbtPluingVersionData(100 milliseconds)
      Thread.sleep(1000)

      scheduler.getCallCount should be > 8
      scheduler.getCallCount should be < 11
    }
  }
}
