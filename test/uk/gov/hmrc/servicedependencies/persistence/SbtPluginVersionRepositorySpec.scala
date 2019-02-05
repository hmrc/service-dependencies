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
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{FailOnUnindexedQueries, MongoConnector, MongoSpecSupport, RepositoryPreparation}
import uk.gov.hmrc.servicedependencies.model.{MongoSbtPluginVersion, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global

class SbtPluginVersionRepositorySpec
    extends WordSpecLike
       with Matchers
       with MongoSpecSupport
       with ScalaFutures
       with BeforeAndAfterEach
       with MockitoSugar
       with FailOnUnindexedQueries
       with RepositoryPreparation {

  val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
    val mockedMongoConnector: MongoConnector = mock[MongoConnector]
    when(mockedMongoConnector.db).thenReturn(mongo)

    override def mongoConnector = mockedMongoConnector
  }

  val futureHelper: FutureHelpers = new MockFutureHelpers()
  val sbtPluginVersionRepository = new SbtPluginVersionRepository(reactiveMongoComponent, futureHelper)

  override def beforeEach() {
    prepare(sbtPluginVersionRepository)
  }

  "update" should {
    "inserts correctly" in {

      val sbtPluginVersion = MongoSbtPluginVersion("some-sbtPlugin", Some(Version(1, 0, 2)), DateTimeUtils.now)
      await(sbtPluginVersionRepository.update(sbtPluginVersion))

      await(sbtPluginVersionRepository.getAllEntries) shouldBe Seq(sbtPluginVersion)
    }

    "updates correctly (based on sbtPlugin name)" in {

      val sbtPluginVersion    = MongoSbtPluginVersion("some-sbtPlugin", Some(Version(1, 0, 2)), DateTimeUtils.now)
      val newSbtPluginVersion = sbtPluginVersion.copy(version = Some(Version(1, 0, 5)))
      await(sbtPluginVersionRepository.update(sbtPluginVersion))

      await(sbtPluginVersionRepository.update(newSbtPluginVersion))

      await(sbtPluginVersionRepository.getAllEntries) shouldBe Seq(newSbtPluginVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val sbtPluginVersion = MongoSbtPluginVersion("some-sbtPlugin", Some(Version(1, 0, 2)), DateTimeUtils.now)
      await(sbtPluginVersionRepository.update(sbtPluginVersion))

      await(sbtPluginVersionRepository.getAllEntries) should have size 1

      await(sbtPluginVersionRepository.clearAllData)

      await(sbtPluginVersionRepository.getAllEntries) shouldBe Nil
    }
  }
}
