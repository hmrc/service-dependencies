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

import com.codahale.metrics.MetricRegistry
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{FailOnUnindexedQueries, MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global

class LibraryVersionRepositorySpec
    extends UnitSpec
    with LoneElement
    with MongoSpecSupport
    with ScalaFutures
    with OptionValues
    with BeforeAndAfterEach
    with MockitoSugar
    with FailOnUnindexedQueries {

  val reactiveMongoComponent = new ReactiveMongoComponent {
    val mockedMongoConnector = mock[MongoConnector]
    when(mockedMongoConnector.db).thenReturn(mongo)

    override def mongoConnector = mockedMongoConnector
  }

  val metricsRegistry = new MetricRegistry()
  val futureHelper: FutureHelpers = new MockFutureHelpers()

  val libraryVersionRepository = new LibraryVersionRepository(reactiveMongoComponent, futureHelper)

  override def beforeEach() {
    await(libraryVersionRepository.drop)
    await(libraryVersionRepository.ensureIndexes)
  }

  "update" should {
    "inserts correctly" in {

      val libraryVersion = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), DateTimeUtils.now)
      await(libraryVersionRepository.update(libraryVersion))

      await(libraryVersionRepository.getAllEntries) shouldBe Seq(libraryVersion)
    }

    "updates correctly (based on library name)" in {

      val libraryVersion    = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), DateTimeUtils.now)
      val newLibraryVersion = libraryVersion.copy(version = Some(Version(1, 0, 5)))
      await(libraryVersionRepository.update(libraryVersion))

      await(libraryVersionRepository.update(newLibraryVersion))

      await(libraryVersionRepository.getAllEntries) shouldBe Seq(newLibraryVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val libraryVersion = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), DateTimeUtils.now)
      await(libraryVersionRepository.update(libraryVersion))

      await(libraryVersionRepository.getAllEntries) should have size 1

      await(libraryVersionRepository.clearAllData)

      await(libraryVersionRepository.getAllEntries) shouldBe Nil
    }
  }
}
