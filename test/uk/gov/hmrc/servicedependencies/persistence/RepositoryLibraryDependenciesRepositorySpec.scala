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

import java.time.temporal.ChronoUnit

import org.mockito.MockitoSugar
import org.mongodb.scala.model.IndexModel
import org.scalatest.{Matchers, WordSpecLike}
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.servicedependencies.model.{MongoRepositoryDependencies, MongoRepositoryDependency, Version}
import uk.gov.hmrc.servicedependencies.util.{DateUtil, FutureHelpers, MockFutureHelpers}

class RepositoryLibraryDependenciesRepositorySpec
    extends WordSpecLike
      with Matchers
      with MockitoSugar
      // We don't mixin IndexedMongoQueriesSupport here, as this repo makes use of regex based queries not satisfied by an index
      with CleanMongoCollectionSupport {

  val futureHelper: FutureHelpers = new MockFutureHelpers()
  val repo = new RepositoryLibraryDependenciesRepository(mongoComponent, futureHelper)

  override protected val collectionName: String   = repo.collectionName
  override protected val indexes: Seq[IndexModel] = repo.indexes

  "update" should {
    "inserts correctly" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2"))),
        Nil,
        Nil,
        DateUtil.now)

      repo.update(repositoryLibraryDependencies).futureValue

      repo.getAllEntries.futureValue shouldBe Seq(repositoryLibraryDependencies)
    }

    "inserts correctly with suffix" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2-play-26"))),
        Nil,
        Nil,
        DateUtil.now)
      repo.update(repositoryLibraryDependencies).futureValue

      repo.getAllEntries.futureValue shouldBe Seq(repositoryLibraryDependencies)
    }



    "updates correctly (based on repository name)" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2"))),
        Nil,
        Nil,
        DateUtil.now)
      val newRepositoryLibraryDependencies = repositoryLibraryDependencies.copy(
        libraryDependencies = repositoryLibraryDependencies.libraryDependencies :+ MongoRepositoryDependency(
          "some-other-lib",
          Version(8, 4, 2)))
      repo.update(repositoryLibraryDependencies).futureValue

      repo.update(newRepositoryLibraryDependencies).futureValue

      repo.getAllEntries.futureValue shouldBe Seq(newRepositoryLibraryDependencies)
    }

    "updates correctly (based on repository name) with suffix" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2"))),
        Nil,
        Nil,
        DateUtil.now)
      val newRepositoryLibraryDependencies = repositoryLibraryDependencies.copy(
        libraryDependencies =
          repositoryLibraryDependencies.libraryDependencies :+ MongoRepositoryDependency(
            "some-other-lib",
            Version("8.4.2-play-26"))
        )
      repo.update(repositoryLibraryDependencies).futureValue

      repo.update(newRepositoryLibraryDependencies).futureValue

      repo.getAllEntries.futureValue shouldBe Seq(newRepositoryLibraryDependencies)
    }
  }

  "getForRepository" should {
    "get back the correct record" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version("1.0.2"))),
        Nil,
        Nil,
        DateUtil.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version("11.0.22"))),
        Nil,
        Nil,
        DateUtil.now)

      repo.update(repositoryLibraryDependencies1).futureValue
      repo.update(repositoryLibraryDependencies2).futureValue

      repo.getForRepository("some-repo1").futureValue shouldBe Some(
        repositoryLibraryDependencies1)
    }

    "finds the repository when the name is of different case" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version("1.0.2"))),
        Nil,
        Nil,
        DateUtil.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version("11.0.22"))),
        Nil,
        Nil,
        DateUtil.now)

      repo.update(repositoryLibraryDependencies1).futureValue
      repo.update(repositoryLibraryDependencies2).futureValue

      repo.getForRepository("SOME-REPO1").futureValue shouldBe defined
    }

    "not find a repository with partial name" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
        "some-repo1",
        Seq(MongoRepositoryDependency("some-lib1", Version("1.0.2"))),
        Nil,
        Nil,
        DateUtil.now)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
        "some-repo2",
        Seq(MongoRepositoryDependency("some-lib2", Version("11.0.22"))),
        Nil,
        Nil,
        DateUtil.now)

      repo.update(repositoryLibraryDependencies1).futureValue
      repo.update(repositoryLibraryDependencies2).futureValue

      repo.getForRepository("some-repo").futureValue shouldBe None
    }
  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
        "some-repo",
        Seq(MongoRepositoryDependency("some-lib", Version("1.0.2"))),
        Nil,
        Nil,
        DateUtil.now)

      repo.update(repositoryLibraryDependencies).futureValue

      repo.getAllEntries.futureValue should have size 1

      repo.clearAllData.futureValue

      repo.getAllEntries.futureValue shouldBe Nil
    }
  }

  "clearUpdateDates" should {
    "resets the last update dates to January 1, 1970" in {

      val t1 = DateUtil.now
      val t2 = DateUtil.now.plus(1, ChronoUnit.DAYS)
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

      repo.update(repositoryLibraryDependencies1).futureValue
      repo.update(repositoryLibraryDependencies2).futureValue

      val mongoRepositoryDependencies = repo.getAllEntries.futureValue
      mongoRepositoryDependencies                   should have size 2
      mongoRepositoryDependencies.map(_.updateDate) should contain theSameElementsAs Seq(t1, t2)

      repo.clearUpdateDates.futureValue

      repo.getAllEntries.futureValue
        .map(_.updateDate) should contain theSameElementsAs Seq(
        DateUtil.epoch,
        DateUtil.epoch)
    }
  }
}
