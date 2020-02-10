/*
 * Copyright 2020 HM Revenue & Customs
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
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.IndexModel
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class LibraryVersionRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with MockitoSugar
    with DefaultPlayMongoRepositorySupport[MongoLibraryVersion] {

  val metricsRegistry             = new MetricRegistry()
  val futureHelper: FutureHelpers = new MockFutureHelpers()

  override lazy val repository = new LibraryVersionRepository(mongoComponent, futureHelper, throttleConfig)

  val libraryVersion = MongoLibraryVersion(
      name       = "some-library"
    , group      = "uk.gov.hmrc"
    , version    = Some(Version(1, 0, 2))
    , updateDate = Instant.now()
    )

  "update" should {
    "inserts correctly" in {
      repository.update(libraryVersion).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(libraryVersion)
    }

    "updates correctly (based on library name)" in {
      val newLibraryVersion = libraryVersion.copy(version = Some(Version(1, 0, 5)))

      repository.update(libraryVersion).futureValue
      repository.update(newLibraryVersion).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(newLibraryVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {
      repository.update(libraryVersion).futureValue
      repository.getAllEntries.futureValue should have size 1
      repository.clearAllData.futureValue
      repository.getAllEntries.futureValue shouldBe Nil
    }
  }
}
