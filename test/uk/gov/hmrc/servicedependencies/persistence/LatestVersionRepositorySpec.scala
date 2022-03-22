/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.{LatestVersion, Version}

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class LatestVersionRepositorySpec
  extends AnyWordSpecLike
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[LatestVersion] {

  val metricsRegistry = new MetricRegistry()

  override lazy val repository = new LatestVersionRepository(mongoComponent)

  val latestVersion = LatestVersion(
      name       = "some-library"
    , group      = "uk.gov.hmrc"
    , version    = Version(1, 0, 2)
    , updateDate = Instant.now()
    )

  "update" should {
    "inserts correctly" in {
      repository.update(latestVersion).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(latestVersion)
      repository.find(group = "uk.gov.hmrc", artefact = "some-library").futureValue shouldBe Some(latestVersion)
    }

    "updates correctly (based on name and group)" in {
      val newLibraryVersion = latestVersion.copy(version = Version(1, 0, 5))

      repository.update(latestVersion).futureValue
      repository.update(newLibraryVersion).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(newLibraryVersion)
      repository.find(group = "uk.gov.hmrc", artefact = "some-library").futureValue shouldBe Some(newLibraryVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "delete everything" in {
      repository.update(latestVersion).futureValue
      repository.getAllEntries.futureValue should have size 1
      repository.clearAllData.futureValue
      repository.getAllEntries.futureValue shouldBe Nil
      repository.find(group = "uk.gov.hmrc", artefact = "some-library").futureValue shouldBe None
    }
  }
}
