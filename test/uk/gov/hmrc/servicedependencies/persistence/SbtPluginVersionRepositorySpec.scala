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

package uk.gov.hmrc.servicedependencies.persistence

import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{MongoSbtPluginVersion, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global

class SbtPluginVersionRepositorySpec
    extends UnitSpec
    with LoneElement
    with MongoSpecSupport
    with ScalaFutures
    with OptionValues
    with BeforeAndAfterEach
    with MockitoSugar {

  val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
    val mockedMongoConnector: MongoConnector = mock[MongoConnector]
    when(mockedMongoConnector.db).thenReturn(mongo)

    override def mongoConnector = mockedMongoConnector
  }

  val futureHelper: FutureHelpers = new MockFutureHelpers()
  val mongoSbtPluginVersions = new SbtPluginVersionRepository(reactiveMongoComponent, futureHelper)

  override def beforeEach() {
    await(mongoSbtPluginVersions.drop)
  }

  "update" should {
    "inserts correctly" in {

      val sbtPluginVersion = MongoSbtPluginVersion("some-sbtPlugin", Some(Version(1, 0, 2)), DateTimeUtils.now)
      await(mongoSbtPluginVersions.update(sbtPluginVersion))

      await(mongoSbtPluginVersions.getAllEntries) shouldBe Seq(sbtPluginVersion)
    }

    "updates correctly (based on sbtPlugin name)" in {

      val sbtPluginVersion    = MongoSbtPluginVersion("some-sbtPlugin", Some(Version(1, 0, 2)), DateTimeUtils.now)
      val newSbtPluginVersion = sbtPluginVersion.copy(version = Some(Version(1, 0, 5)))
      await(mongoSbtPluginVersions.update(sbtPluginVersion))

      await(mongoSbtPluginVersions.update(newSbtPluginVersion))

      await(mongoSbtPluginVersions.getAllEntries) shouldBe Seq(newSbtPluginVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val sbtPluginVersion = MongoSbtPluginVersion("some-sbtPlugin", Some(Version(1, 0, 2)), DateTimeUtils.now)
      await(mongoSbtPluginVersions.update(sbtPluginVersion))

      await(mongoSbtPluginVersions.getAllEntries) should have size 1

      await(mongoSbtPluginVersions.clearAllData)

      await(mongoSbtPluginVersions.getAllEntries) shouldBe Nil
    }
  }
}
