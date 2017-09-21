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

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.MetricsImpl
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues, TestData}
import org.scalatestplus.play.OneAppPerTest
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.reactivemongo.{ReactiveMongoComponent, ReactiveMongoComponentImpl, ReactiveMongoHmrcModule}
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.bootstrap.filters.MicroserviceFilters
import uk.gov.hmrc.play.bootstrap.{AuditModule, AuthModule, HttpClientModule, MicroserviceModule}
import uk.gov.hmrc.play.graphite.{GraphiteMetricsImpl, GraphiteMetricsModule}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.SchedulerModule
import uk.gov.hmrc.servicedependencies.TestHelpers._
import uk.gov.hmrc.servicedependencies.model.{LibraryDependency, MongoRepositoryDependencies, Version}
import play.api.inject.bind
import uk.gov.hmrc.play.bootstrap.http.JsonErrorHandler

trait GuiceOneAppPerTest extends MockitoSugar { self :OneAppPerTest =>
  implicit override def newAppForTest(testData: TestData): Application =
    new GuiceApplicationBuilder().disable(
      classOf[HttpClientModule],
      classOf[MicroserviceModule],
      classOf[AuthModule],
      classOf[MetricRegistry],
      classOf[MetricsImpl],
      classOf[GraphiteMetricsModule]
      //      classOf[com.kenshoo.play.metrics.PlayModule],
      //      classOf[AuditModule],
      //      classOf[SchedulerModule],
      //      classOf[GraphiteMetricsImpl],
      //      classOf[ReactiveMongoHmrcModule],
      //      classOf[ReactiveMongoComponentImpl]
    ).overrides(
      bind[MicroserviceFilters].toInstance(mock[MicroserviceFilters]),
      bind[JsonErrorHandler].toInstance(mock[JsonErrorHandler])
    ).build()

}

class RepositoryLibraryDependenciesRepositorySpec extends UnitSpec with LoneElement with MongoSpecSupport with ScalaFutures with OptionValues with BeforeAndAfterEach with OneAppPerTest with MockitoSugar with GuiceOneAppPerTest {



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

      val repositoryLibraryDependencies = MongoRepositoryDependencies("some-repo", Seq(LibraryDependency("some-lib", Version(1, 0, 2))), Nil, Nil, None)
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(repositoryLibraryDependencies)
    }

    "updates correctly (based on repository name)" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies("some-repo", Seq(LibraryDependency("some-lib", Version(1, 0, 2))), Nil, Nil, None)
      val newRepositoryLibraryDependencies = repositoryLibraryDependencies.copy(libraryDependencies = repositoryLibraryDependencies.libraryDependencies :+ LibraryDependency("some-other-lib", Version(8, 4, 2)) )
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.update(newRepositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Seq(newRepositoryLibraryDependencies)
    }
  }

  "getForRepository" should {
    "get back the correct record" in {
      val repositoryLibraryDependencies1 = MongoRepositoryDependencies("some-repo1", Seq(LibraryDependency("some-lib1", Version(1, 0, 2))), Nil, Nil, None)
      val repositoryLibraryDependencies2 = MongoRepositoryDependencies("some-repo2", Seq(LibraryDependency("some-lib2", Version(11, 0, 22))), Nil, Nil, None)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      await(mongoRepositoryLibraryDependenciesRepository.getForRepository("some-repo1")) shouldBe Some(repositoryLibraryDependencies1)
    }

  }

  "clearAllDependencyEntries" should {
    "deletes everything" in {

      val repositoryLibraryDependencies = MongoRepositoryDependencies("some-repo", Seq(LibraryDependency("some-lib", Version(1, 0, 2))), Nil, Nil, None)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies))

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) should have size 1

      await(mongoRepositoryLibraryDependenciesRepository.clearAllData)

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries) shouldBe Nil
    }
  }

  "clearAllGithubLastUpdateDates" should {
    "resets the last update dates to None" in {

      val someDate = Some(toDate(LocalDateTime.now()))
      val repositoryLibraryDependencies1 =
        MongoRepositoryDependencies("some-repo", Seq(LibraryDependency("some-lib2", Version(1, 0, 2))), Nil, Nil, someDate)
      val repositoryLibraryDependencies2 =
        MongoRepositoryDependencies("some-other-repo", Seq(LibraryDependency("some-lib2", Version(1, 0, 2))), Nil, Nil, someDate)

      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies1))
      await(mongoRepositoryLibraryDependenciesRepository.update(repositoryLibraryDependencies2))

      val mongoRepositoryDependencieses = await(mongoRepositoryLibraryDependenciesRepository.getAllEntries)
      mongoRepositoryDependencieses should have size 2
      mongoRepositoryDependencieses.map(_.lastGitUpdateDate) should contain theSameElementsAs Seq(someDate, someDate)

      await(mongoRepositoryLibraryDependenciesRepository.clearAllGithubLastUpdateDates)

      await(mongoRepositoryLibraryDependenciesRepository.getAllEntries).map(_.lastGitUpdateDate) should contain theSameElementsAs Seq(None, None)
    }
  }
}
