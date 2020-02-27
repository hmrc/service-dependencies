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
import uk.gov.hmrc.servicedependencies.model.{MongoLatestVersion, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class LatestVersionRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with MockitoSugar
    with DefaultPlayMongoRepositorySupport[MongoLatestVersion] {

  val metricsRegistry             = new MetricRegistry()
  val futureHelper: FutureHelpers = new MockFutureHelpers()

  override lazy val repository = new LatestVersionRepository(mongoComponent, futureHelper)

  val latestVersion = MongoLatestVersion(
      name       = "some-library"
    , group      = "uk.gov.hmrc"
    , version    = Version(1, 0, 2)
    , updateDate = Instant.now()
    )

  "update" should {
    "inserts correctly" in {
      repository.update(latestVersion).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(latestVersion)
    }

    "updates correctly (based on name and group)" in {
      val newLibraryVersion = latestVersion.copy(version = Version(1, 0, 5))

      repository.update(latestVersion).futureValue
      repository.update(newLibraryVersion).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(newLibraryVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "delete everything" in {
      repository.update(latestVersion).futureValue
      repository.getAllEntries.futureValue should have size 1
      repository.clearAllData.futureValue
      repository.getAllEntries.futureValue shouldBe Nil
    }
  }
}
