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

import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{FailOnUnindexedQueries, MongoConnector, MongoSpecSupport, RepositoryPreparation}
import uk.gov.hmrc.servicedependencies.model.{MongoRepositoryDependencies, MongoRepositoryDependency, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global

class RepositoryLibraryDependenciesRepositorySpec
    extends WordSpecLike
       with Matchers
       with MongoSpecSupport
       with ScalaFutures
       with BeforeAndAfterEach
       with MockitoSugar
       //with FailOnUnindexedQueries // case insensitive search is unindexed
       with RepositoryPreparation {

  val mockMongoConnector         = mock[MongoConnector]
  val mockReactiveMongoComponent = mock[ReactiveMongoComponent]

  when(mockMongoConnector.db).thenReturn(mongo)
  when(mockReactiveMongoComponent.mongoConnector).thenReturn(mockMongoConnector)

  val futureHelper: FutureHelpers = new MockFutureHelpers()
  val repositoryLibraryDependenciesRepository = new RepositoryLibraryDependenciesRepository(mockReactiveMongoComponent, futureHelper)

  override def beforeEach() {
    prepare(repositoryLibraryDependenciesRepository)
  }

  "update" should {
    "inserts correctly" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2"))),
        Nil,
        Nil,
        DateTimeUtils.now)
      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(repositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(repositoryLibraryDependencies)
    }

    "inserts correctly with suffix" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2-play-26"))),
        Nil,
        Nil,
        DateTimeUtils.now)
      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(repositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(repositoryLibraryDependencies)
    }



    "updates correctly (based on repository name)" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2"))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val newRepositoryLibraryDependencies = repositoryLibraryDependencies.copy(
        libraryDependencies = repositoryLibraryDependencies.libraryDependencies :+ MongoRepositoryDependency(
          "some-other-lib",
          Version(8, 4, 2)))
      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(repositoryLibraryDependenciesRepository.update(newRepositoryLibraryDependencies))

      await(repositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(newRepositoryLibraryDependencies)
    }

    "updates correctly (based on repository name) with suffix" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2"))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val newRepositoryLibraryDependencies = repositoryLibraryDependencies.copy(
        libraryDependencies =
          repositoryLibraryDependencies.libraryDependencies :+ MongoRepositoryDependency(
            "some-other-lib",
            Version("8.4.2-play-26"))
        )
      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(repositoryLibraryDependenciesRepository.update(newRepositoryLibraryDependencies))

      await(repositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(newRepositoryLibraryDependencies)
    }
  }

  "getForRepository" should {
    "get back the correct record" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version("1.0.2"))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version("11.0.22"))),
        Nil,
        Nil,
        DateTimeUtils.now)

      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      await(repositoryLibraryDependenciesRepository.getForRepository("some-repo1")) shouldBe Some(
        repositoryLibraryDependencies1)
    }

    "finds the repository when the name is of different case" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version("1.0.2"))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version("11.0.22"))),
        Nil,
        Nil,
        DateTimeUtils.now)

      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      await(repositoryLibraryDependenciesRepository.getForRepository("SOME-REPO1")) shouldBe defined
    }

    "not find a repository with partial name" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version("1.0.2"))),
        Nil,
        Nil,
        DateTimeUtils.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version("11.0.22"))),
        Nil,
        Nil,
        DateTimeUtils.now)

      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      await(repositoryLibraryDependenciesRepository.getForRepository("some-repo")) shouldBe None
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2"))),
        Nil,
        Nil,
        DateTimeUtils.now)

      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(repositoryLibraryDependenciesRepository.getAllEntries) should have size 1

      await(repositoryLibraryDependenciesRepository.clearAllData)

      await(repositoryLibraryDependenciesRepository.getAllEntries) shouldBe Nil
    }
  }

  "clearUpdateDates" should {
    "resets the last update dates to January 1, 1970" in {

      val t1 = DateTimeUtils.now
      val t2 = DateTimeUtils.now.plusDays(1)
      val repositoryLibraryDependencies1 =
        MongoRepositoryDependencies(
          "some-repo",
          Seq(MongoRepositoryDependency("some-lib2", Version("1.0.2"))),
          Nil,
          Nil,
          t1)
      val repositoryLibraryDependencies2 =
        MongoRepositoryDependencies(
          "some-other-repo",
          Seq(MongoRepositoryDependency("some-lib2", Version("1.0.2"))),
          Nil,
          Nil,
          t2)

      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(repositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      val mongoRepositoryDependencieses = await(repositoryLibraryDependenciesRepository.getAllEntries)
      mongoRepositoryDependencieses                   should have size 2
      mongoRepositoryDependencieses.map(_.updateDate) should contain theSameElementsAs Seq(t1, t2)

      await(repositoryLibraryDependenciesRepository.clearUpdateDates)

      await(repositoryLibraryDependenciesRepository.getAllEntries)
        .map(_.updateDate) should contain theSameElementsAs Seq(
        new DateTime(0, DateTimeZone.UTC),
        new DateTime(0, DateTimeZone.UTC))
    }
  }
}
