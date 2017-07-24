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

package uk.gov.hmrc.cataloguefrontend.events


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



import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import org.scalatestplus.play.OneAppPerTest
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, Version}
import uk.gov.hmrc.servicedependencies.presistence.MongoLibraryVersionRepository

import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MongoLibraryVersionRepositorySpec extends UnitSpec with LoneElement with MongoSpecSupport with ScalaFutures with OptionValues with BeforeAndAfterEach with OneAppPerTest {

  val mongoLibraryVersions = new MongoLibraryVersionRepository(mongo)

  override def beforeEach() {
    await(mongoLibraryVersions.drop)
  }

  "update" should {
    "inserts correctly" in {

      val libraryVersion = MongoLibraryVersion("some-library", Version(1, 0, 2))
      await(mongoLibraryVersions.update(libraryVersion))

      await(mongoLibraryVersions.getAllDependencyEntries) shouldBe Seq(libraryVersion)
    }

    "updates correctly (based on library name)" in {

      val libraryVersion = MongoLibraryVersion("some-library", Version(1, 0, 2))
      val newLibraryVersion = libraryVersion.copy(version = Version(1, 0, 5))
      await(mongoLibraryVersions.update(libraryVersion))

      await(mongoLibraryVersions.update(newLibraryVersion))

      await(mongoLibraryVersions.getAllDependencyEntries) shouldBe Seq(newLibraryVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val libraryVersion = MongoLibraryVersion("some-library", Version(1, 0, 2))
      await(mongoLibraryVersions.update(libraryVersion))

      await(mongoLibraryVersions.getAllDependencyEntries) should have size 1

      await(mongoLibraryVersions.clearAllData)

      await(mongoLibraryVersions.getAllDependencyEntries) shouldBe Nil
    }
  }
}
