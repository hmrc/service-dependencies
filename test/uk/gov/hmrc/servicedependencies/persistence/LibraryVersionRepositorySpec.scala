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

import java.time.Instant

import com.codahale.metrics.MetricRegistry
import org.mockito.MockitoSugar
import org.mongodb.scala.model.IndexModel
import org.scalatest.{Matchers, WordSpecLike}
import play.api.Configuration
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport
import uk.gov.hmrc.mongo.throttle.ThrottleConfig
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}

class LibraryVersionRepositorySpec
    extends WordSpecLike
    with Matchers
    with MockitoSugar
    with DefaultMongoCollectionSupport {

  val metricsRegistry = new MetricRegistry()
  val futureHelper    = new MockFutureHelpers()
  val throttleConfig  = new ThrottleConfig(Configuration())
  val repo            = new LibraryVersionRepository(mongoComponent, futureHelper, throttleConfig)

  override protected val collectionName: String   = repo.collectionName
  override protected val indexes: Seq[IndexModel] = repo.indexes

  "update" should {
    "inserts correctly" in {
      val libraryVersion = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), Instant.now())

      repo.update(libraryVersion).futureValue
      repo.getAllEntries.futureValue shouldBe Seq(libraryVersion)
    }

    "updates correctly (based on library name)" in {
      val libraryVersion    = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), Instant.now())
      val newLibraryVersion = libraryVersion.copy(version = Some(Version(1, 0, 5)))

      repo.update(libraryVersion).futureValue
      repo.update(newLibraryVersion).futureValue
      repo.getAllEntries.futureValue shouldBe Seq(newLibraryVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {
      val libraryVersion = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), Instant.now())

      repo.update(libraryVersion).futureValue
      repo.getAllEntries.futureValue should have size 1
      repo.clearAllData.futureValue
      repo.getAllEntries.futureValue shouldBe Nil
    }
  }
}
