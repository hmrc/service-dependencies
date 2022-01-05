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
import java.time.temporal.ChronoUnit

import org.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}
import uk.gov.hmrc.servicedependencies.model.{MongoRepositoryDependencies, MongoRepositoryDependency, Version}
import uk.gov.hmrc.servicedependencies.util.{FutureHelpers, MockFutureHelpers}

import scala.concurrent.duration.DurationInt

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RepositoryDependenciesRepositorySpec
    extends AnyWordSpecLike
      with Matchers
      with MockitoSugar
      // We don't mixin IndexedMongoQueriesSupport here, as this repo makes use of regex based queries not satisfied by an index
      with PlayMongoRepositorySupport[MongoRepositoryDependencies]
      with CleanMongoCollectionSupport {

  val futureHelper: FutureHelpers = new MockFutureHelpers()
  override lazy val repository = new RepositoryDependenciesRepository(mongoComponent, futureHelper)

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)

  "update" should {
    "insert correctly" in {
      val repositoryLibraryDependencies = MongoRepositoryDependencies(
          repositoryName        = "some-repo"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )

      repository.update(repositoryLibraryDependencies).futureValue

      repository.getAllEntries.futureValue shouldBe Seq(repositoryLibraryDependencies)
    }

    "insert correctly with suffix" in {
      val repositoryLibraryDependencies = MongoRepositoryDependencies(
          repositoryName        = "some-repo"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib", group = "uk.gov.hmrc", currentVersion = Version("1.0.2-play-26")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )
      repository.update(repositoryLibraryDependencies).futureValue

      repository.getAllEntries.futureValue shouldBe Seq(repositoryLibraryDependencies)
    }

    "update correctly (based on repository name)" in {
      val repositoryLibraryDependencies = MongoRepositoryDependencies(
          repositoryName        = "some-repo"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )
      val newRepositoryLibraryDependencies =
        repositoryLibraryDependencies.copy(
          libraryDependencies = repositoryLibraryDependencies.libraryDependencies :+ MongoRepositoryDependency(
              name           = "some-other-lib"
            , group          = "uk.gov.hmrc"
            , currentVersion = Version(8, 4, 2)
            )
        )
      repository.update(repositoryLibraryDependencies).futureValue

      repository.update(newRepositoryLibraryDependencies).futureValue

      repository.getAllEntries.futureValue shouldBe Seq(newRepositoryLibraryDependencies)
    }

    "update correctly (based on repository name) with suffix" in {
      val repositoryLibraryDependencies = MongoRepositoryDependencies(
          repositoryName        = "some-repo"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )
      val newRepositoryLibraryDependencies =
        repositoryLibraryDependencies.copy(
          libraryDependencies = repositoryLibraryDependencies.libraryDependencies :+ MongoRepositoryDependency(
              name           = "some-other-lib"
            , group          = "uk.gov.hmrc"
            , currentVersion = Version("8.4.2-play-26")
            )
        )
      repository.update(repositoryLibraryDependencies).futureValue

      repository.update(newRepositoryLibraryDependencies).futureValue

      repository.getAllEntries.futureValue shouldBe Seq(newRepositoryLibraryDependencies)
    }
  }

  "getForRepository" should {
    "get back the correct record" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
          repositoryName        = "some-repo1"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib1", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
          repositoryName        = "some-repo2"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib2", group = "uk.gov.hmrc", currentVersion = Version("11.0.22")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )

      repository.update(repositoryLibraryDependencies1).futureValue
      repository.update(repositoryLibraryDependencies2).futureValue

      repository.getForRepository("some-repo1").futureValue shouldBe Some(
        repositoryLibraryDependencies1
      )
    }

    "find the repository when the name is of different case" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
          repositoryName        = "some-repo1"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib1", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
          repositoryName        = "some-repo2"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib2", group = "uk.gov.hmrc", currentVersion = Version("11.0.22")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )

      repository.update(repositoryLibraryDependencies1).futureValue
      repository.update(repositoryLibraryDependencies2).futureValue

      repository.getForRepository("SOME-REPO1").futureValue shouldBe Some(
        repositoryLibraryDependencies1
      )
    }

    "not find a repository with partial name" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies(
          repositoryName        = "some-repo1"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib1", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies(
          repositoryName        = "some-repo2"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib2", group = "uk.gov.hmrc", currentVersion = Version("11.0.22")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )

      repository.update(repositoryLibraryDependencies1).futureValue
      repository.update(repositoryLibraryDependencies2).futureValue

      repository.getForRepository("some-repo").futureValue shouldBe None
    }
  }

  "clearAllDependencyEntries" should {
    "delete everything" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies(
          repositoryName        = "some-repo"
        , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
        , sbtPluginDependencies = Nil
        , otherDependencies     = Nil
        , updateDate            = Instant.now()
        )

      repository.update(repositoryLibraryDependencies).futureValue

      repository.getAllEntries.futureValue should have size 1

      repository.clearAllData.futureValue

      repository.getAllEntries.futureValue shouldBe Nil
    }
  }

  "clearUpdateDates" should {
    "reset the last update dates to January 1, 1970" in {

      val t1 = Instant.now()
      val t2 = Instant.now().plus(1, ChronoUnit.DAYS)
      val repositoryLibraryDependencies1 =
        MongoRepositoryDependencies(
            repositoryName        = "some-repo"
          , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib2", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
          , sbtPluginDependencies = Nil
          , otherDependencies     = Nil
          , updateDate            = t1
          )
      val repositoryLibraryDependencies2 =
        MongoRepositoryDependencies(
            repositoryName        = "some-other-repo"
          , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib2", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
          , sbtPluginDependencies = Nil
          , otherDependencies     = Nil
          , updateDate            = t2
          )

      repository.update(repositoryLibraryDependencies1).futureValue
      repository.update(repositoryLibraryDependencies2).futureValue

      val mongoRepositoryDependencies = repository.getAllEntries.futureValue
      mongoRepositoryDependencies                   should have size 2
      mongoRepositoryDependencies.map(_.updateDate) should contain theSameElementsAs Seq(t1, t2)

      repository.clearUpdateDates.futureValue

      repository.getAllEntries.futureValue
        .map(_.updateDate) should contain theSameElementsAs Seq(
          Instant.EPOCH
        , Instant.EPOCH
        )
    }
  }

  "clearUpdateDatesForRepository" should {
    "reset the last update dates to January 1, 1970" in {

      val t1 = Instant.now()
      val t2 = Instant.now().plus(1, ChronoUnit.DAYS)
      val repositoryLibraryDependencies1 =
        MongoRepositoryDependencies(
            repositoryName        = "some-repo"
          , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib2", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
          , sbtPluginDependencies = Nil
          , otherDependencies     = Nil
          , updateDate            = t1
          )
      val repositoryLibraryDependencies2 =
        MongoRepositoryDependencies(
            repositoryName        = "some-other-repo"
          , libraryDependencies   = Seq(MongoRepositoryDependency(name = "some-lib2", group = "uk.gov.hmrc", currentVersion = Version("1.0.2")))
          , sbtPluginDependencies = Nil
          , otherDependencies     = Nil
          , updateDate            = t2
          )

      repository.update(repositoryLibraryDependencies1).futureValue
      repository.update(repositoryLibraryDependencies2).futureValue

      val mongoRepositoryDependencies = repository.getAllEntries.futureValue
      mongoRepositoryDependencies                   should have size 2
      mongoRepositoryDependencies.map(_.updateDate) should contain theSameElementsAs Seq(t1, t2)

      repository.clearUpdateDatesForRepository(repositoryLibraryDependencies2.repositoryName).futureValue

      repository.getForRepository(repositoryLibraryDependencies1.repositoryName).futureValue shouldBe Some(
        repositoryLibraryDependencies1
      )
      repository.getForRepository(repositoryLibraryDependencies2.repositoryName).futureValue shouldBe Some(
        repositoryLibraryDependencies2.copy(updateDate = Instant.EPOCH)
      )
    }
  }
}
