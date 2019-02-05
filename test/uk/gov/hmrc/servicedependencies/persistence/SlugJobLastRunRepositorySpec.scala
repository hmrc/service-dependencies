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

import org.joda.time.Instant
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{FailOnUnindexedQueries, MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class SlugJobLastRunRepositorySpec
    extends UnitSpec
       with LoneElement
       with MongoSpecSupport
       with ScalaFutures
       with OptionValues
       with BeforeAndAfterEach
       with GuiceOneAppPerSuite
       with MockitoSugar
       with FailOnUnindexedQueries {

  val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
    val mockedMongoConnector: MongoConnector = mock[MongoConnector]
    when(mockedMongoConnector.db).thenReturn(mongo)

    override def mongoConnector = mockedMongoConnector
  }

  override def fakeApplication(): Application = GuiceApplicationBuilder()
    .configure("metrics.jvm" -> false)
    .build()

  val slugJobLastRunRepository = new SlugJobLastRunRepository(reactiveMongoComponent)

  override def beforeEach() {
    await(slugJobLastRunRepository.drop)
    await(slugJobLastRunRepository.ensureIndexes)
  }

  "SlugJobLastRunRepository" should {
    "store last run date time" in {
      await(slugJobLastRunRepository.getLastRun) shouldBe None

      val i1 = Instant.now
      await(slugJobLastRunRepository.setLastRun(i1)) shouldBe true
      await(slugJobLastRunRepository.getLastRun) shouldBe Some(i1)

      val i2 = Instant.now
      await(slugJobLastRunRepository.setLastRun(i2)) shouldBe true
      await(slugJobLastRunRepository.getLastRun) shouldBe Some(i2)
    }
  }
}
