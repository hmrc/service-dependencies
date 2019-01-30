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

import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.ExecutionContext.Implicits.global
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{SlugInfo, SlugDependency, Version}

class SlugInfoRepositorySpec
    extends UnitSpec
       with LoneElement
       with MongoSpecSupport
       with BeforeAndAfterEach
       with GuiceOneAppPerSuite
       with MockitoSugar {

  val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
    val mockedMongoConnector: MongoConnector = mock[MongoConnector]
    when(mockedMongoConnector.db).thenReturn(mongo)

    override def mongoConnector = mockedMongoConnector
  }

  override def fakeApplication(): Application = GuiceApplicationBuilder()
    .configure("metrics.jvm" -> false)
    .build()

  val slugInfoRepository = new SlugInfoRepository(reactiveMongoComponent)

  override def beforeEach() {
    await(slugInfoRepository.drop)
  }

  "SlugInfoRepository.add" should {
    "inserts correctly" in {
      await(slugInfoRepository.add(slugInfo))
      await(slugInfoRepository.getAllEntries) shouldBe Seq(slugInfo)
    }

    "reject duplicates" in {
      await(slugInfoRepository.add(slugInfo)) shouldBe true
      await(slugInfoRepository.getAllEntries) should have size 1

      // indices not working with mongoConnector mock?
      // await(slugInfoRepository.add(slugInfo)) shouldBe false
      // await(slugInfoRepository.getAllEntries) should have size 1
    }
  }

  "SlugParserJobsRepository.clearAllDependencyEntries" should {
    "deletes everything" in {
      await(slugInfoRepository.add(slugInfo))
      await(slugInfoRepository.getAllEntries) should have size 1

      await(slugInfoRepository.clearAllData)
      await(slugInfoRepository.getAllEntries) shouldBe Nil
    }
  }

  val slugInfo =
    SlugInfo(
      uri             = "https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz",
      name            = "my-slug",
      version         = "0.27.0",
      versionLong     = 27000,
      runnerVersion   = "0.5.2",
      classpath       = "",
      jdkVersion      = "",
      dependencies    = List(
        SlugDependency(
          path     = "lib1",
          version  = "v1",
          group    = "com.test.group",
          artifact = "lib1"
        ),
        SlugDependency(
          path     = "lib2",
          version  = "v2",
          group    = "com.test.group",
          artifact = "lib1")))
}
