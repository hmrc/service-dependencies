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

import org.mockito.MockitoSugar
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.IndexModel
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.{MongoDependencyVersion, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SbtPluginVersionRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with MockitoSugar
    with DefaultPlayMongoRepositorySupport[MongoDependencyVersion] {

  val futureHelper: FutureHelpers = new MockFutureHelpers()
  override protected lazy val repository = new SbtPluginVersionRepository(mongoComponent, futureHelper, throttleConfig)

  val sbtPluginVersion = MongoDependencyVersion(
      name       = "some-sbtPlugin"
    , group      = "uk.gov.hmrc"
    , version    = Some(Version(1, 0, 2))
    , updateDate = Instant.now()
    )

  "update" should {
    "inserts correctly" in {
      repository.update(sbtPluginVersion).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(sbtPluginVersion)
    }

    "updates correctly (based on sbtPlugin name)" in {
      val newSbtPluginVersion = sbtPluginVersion.copy(version = Some(Version(1, 0, 5)))

      repository.update(sbtPluginVersion).futureValue
      repository.update(newSbtPluginVersion).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(newSbtPluginVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {
      repository.update(sbtPluginVersion).futureValue
      repository.getAllEntries.futureValue should have size 1
      repository.clearAllData.futureValue
      repository.getAllEntries.futureValue shouldBe Nil
    }
  }
}
