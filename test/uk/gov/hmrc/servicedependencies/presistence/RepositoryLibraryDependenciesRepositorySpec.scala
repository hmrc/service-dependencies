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

package uk.gov.hmrc.servicedependencies.presistence

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



import java.time.LocalDateTime

import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import org.scalatestplus.play.OneAppPerTest
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{LibraryDependency, MongoRepositoryDependencies, Version}
import uk.gov.hmrc.time.DateTimeUtils

class RepositoryLibraryDependenciesRepositorySpec extends UnitSpec with LoneElement with MongoSpecSupport with ScalaFutures with OptionValues with BeforeAndAfterEach with OneAppPerTest with MockitoSugar {



  val reactiveMongoComponent = new ReactiveMongoComponent {
    val mockedMongoConnector = mock[MongoConnector]
    when(mockedMongoConnector.db).thenReturn(mongo)

    override def mongoConnector = mockedMongoConnector
  }


  val mongoRepositoryLibraryDependenciesRepository = new RepositoryLibraryDependenciesRepository(reactiveMongoComponent)

  override def beforeEach() {
    await(mongoRepositoryLibraryDependenciesRepository.drop)
  }

  "update" should {
    "inserts correctly" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies("some-repo", Seq(LibraryDependency("some-lib", Version(1, 0, 2))), Nil, Nil, DateTimeUtils.now)
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(repositoryLibraryDependencies)
    }

    "updates correctly (based on repository name)" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies("some-repo", Seq(LibraryDependency("some-lib", Version(1, 0, 2))), Nil, Nil, DateTimeUtils.now)
      val newRepositoryLibraryDependencies = repositoryLibraryDependencies.copy(libraryDependencies = repositoryLibraryDependencies.libraryDependencies :+ LibraryDependency("some-other-lib", Version(8, 4, 2)) )
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.update(newRepositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(newRepositoryLibraryDependencies)
    }
  }

  "getForRepository" should {
    "get back the correct record" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies("some-repo1", Seq(LibraryDependency("some-lib1", Version(1, 0, 2))), Nil, Nil, DateTimeUtils.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies("some-repo2", Seq(LibraryDependency("some-lib2", Version(11, 0, 22))), Nil, Nil, DateTimeUtils.now)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      await(mongoRepositoryLibraryDependenciesRepository.getForRepository("some-repo1")) shouldBe Some(repositoryLibraryDependencies1)
    }

  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies("some-repo", Seq(LibraryDependency("some-lib", Version(1, 0, 2))), Nil, Nil, DateTimeUtils.now)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) should have size 1

      await(mongoRepositoryLibraryDependenciesRepository.clearAllData)

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Nil
    }
  }

  "clearUpdateDates" should {
    "resets the last update dates to January 1, 1970" in {

      val t1 = DateTimeUtils.now
      val t2 = DateTimeUtils.now.plusDays(1)
      val repositoryLibraryDependencies1 =
        MongoRepositoryDependencies("some-repo", Seq(LibraryDependency("some-lib2", Version(1, 0, 2))), Nil, Nil, t1)
      val repositoryLibraryDependencies2 =
        MongoRepositoryDependencies("some-other-repo", Seq(LibraryDependency("some-lib2", Version(1, 0, 2))), Nil, Nil, t2)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      val mongoRepositoryDependencieses = await(mongoRepositoryLibraryDependenciesRepository.getAllEntries)
      mongoRepositoryDependencieses should have size 2
      mongoRepositoryDependencieses.map(_.updateDate) should contain theSameElementsAs Seq(t1, t2)

      await(mongoRepositoryLibraryDependenciesRepository.clearUpdateDates)

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries).map(_.updateDate) should contain theSameElementsAs Seq(new DateTime(0, DateTimeZone.UTC), new DateTime(0, DateTimeZone.UTC))
    }
  }
}
