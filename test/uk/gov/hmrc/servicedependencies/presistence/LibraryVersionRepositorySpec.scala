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



import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import org.scalatestplus.play.OneAppPerTest
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, Version}
import uk.gov.hmrc.servicedependencies.presistence.LibraryVersionRepository
import uk.gov.hmrc.time.DateTimeUtils

class LibraryVersionRepositorySpec extends UnitSpec with LoneElement with MongoSpecSupport with ScalaFutures with OptionValues with BeforeAndAfterEach with OneAppPerTest with MockitoSugar {

  val reactiveMongoComponent = new ReactiveMongoComponent {
    val mockedMongoConnector = mock[MongoConnector]
    when(mockedMongoConnector.db).thenReturn(mongo)

    override def mongoConnector = mockedMongoConnector
  }


  val mongoLibraryVersions = new LibraryVersionRepository(reactiveMongoComponent)

  override def beforeEach() {
    await(mongoLibraryVersions.drop)
  }

  "update" should {
    "inserts correctly" in {

      val libraryVersion = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), DateTimeUtils.now)
      await(mongoLibraryVersions.update(libraryVersion))

      await(mongoLibraryVersions.getAllEntries) shouldBe Seq(libraryVersion)
    }

    "updates correctly (based on library name)" in {

      val libraryVersion = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), DateTimeUtils.now)
      val newLibraryVersion = libraryVersion.copy(version = Some(Version(1, 0, 5)))
      await(mongoLibraryVersions.update(libraryVersion))

      await(mongoLibraryVersions.update(newLibraryVersion))

      await(mongoLibraryVersions.getAllEntries) shouldBe Seq(newLibraryVersion)
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val libraryVersion = MongoLibraryVersion("some-library", Some(Version(1, 0, 2)), DateTimeUtils.now)
      await(mongoLibraryVersions.update(libraryVersion))

      await(mongoLibraryVersions.getAllEntries) should have size 1

      await(mongoLibraryVersions.clearAllData)

      await(mongoLibraryVersions.getAllEntries) shouldBe Nil
    }
  }
}
