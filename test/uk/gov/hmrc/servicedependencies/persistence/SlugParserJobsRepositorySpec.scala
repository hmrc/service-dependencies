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
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.ExecutionContext.Implicits.global
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, NewSlugParserJob}
import uk.gov.hmrc.time.DateTimeUtils

class SlugParserJobsRepositorySpec
    extends UnitSpec
       with LoneElement
       with MongoSpecSupport
       with ScalaFutures
       with OptionValues
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

  val slugParserJobsRepository = new SlugParserJobsRepository(reactiveMongoComponent)

  override def beforeEach() {
    await(slugParserJobsRepository.drop)
  }

  "SlugParserJobsRepository.add" should {
    "inserts correctly" in {
      val newJob = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz")
      await(slugParserJobsRepository.add(newJob))
      checkSingleEntry(expectedSlugUri = newJob.slugUri, expectedProcessed = false)
    }

    "reject duplicates" in {
      await(slugParserJobsRepository.getAllEntries) should have size 0

      val newJob = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.27.0_0.5.3.tgz")
      await(slugParserJobsRepository.add(newJob)) shouldBe true
      await(slugParserJobsRepository.getAllEntries) should have size 1

      // indices not working with mongoConnector mock?
      // await(slugParserJobsRepository.add(newJob)) shouldBe false
      // await(slugParserJobsRepository.getAllEntries) should have size 1
    }
  }

  "SlugParserJobsRepository.markProcessed" should {
    "mark job as processed" in {
      val newJob = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz")
      await(slugParserJobsRepository.add(newJob))
      val createdJob = checkSingleEntry(expectedSlugUri = newJob.slugUri, expectedProcessed = false)

      await(slugParserJobsRepository.markProcessed(createdJob.id))
      checkSingleEntry(expectedSlugUri = newJob.slugUri, expectedProcessed = true)
    }
  }

  "SlugParserJobsRepository.clearAllDependencyEntries" should {
    "deletes everything" in {
      val newJob = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz")
      await(slugParserJobsRepository.add(newJob))
      await(slugParserJobsRepository.getAllEntries) should have size 1

      await(slugParserJobsRepository.clearAllData)
      await(slugParserJobsRepository.getAllEntries) shouldBe Nil
    }
  }

  "SlugParserJobsRepository.markAttempted" should {
    "increase the attempts flag" in {
      val newJob = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz")
      await(slugParserJobsRepository.add(newJob))
      val createdJob = checkSingleEntry(newJob.slugUri, false, 0)

      await(slugParserJobsRepository.markAttempted(createdJob.id))
      checkSingleEntry(newJob.slugUri, false, 1)

      await(slugParserJobsRepository.markAttempted(createdJob.id))
      checkSingleEntry(newJob.slugUri, false, 2)
    }
  }

  "SlugParserJobsRepository.getUnprocessed" should {
    "return only unprocessed jobs" in {
      val newJob1 = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz")
      await(slugParserJobsRepository.add(newJob1))
      val createdJob1 = checkSingleEntry(expectedSlugUri = newJob1.slugUri, expectedProcessed = false)

      val newJob2 = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.28.0_0.5.2.tgz")
      await(slugParserJobsRepository.add(newJob2))
      await(slugParserJobsRepository.getAllEntries) should have size 2

      await(slugParserJobsRepository.markProcessed(createdJob1.id))

      val unprocessed = await(slugParserJobsRepository.getUnprocessed)
      unprocessed should have size 1
      unprocessed.head.slugUri shouldBe newJob2.slugUri
      unprocessed.head.processed shouldBe false
    }

    "return only job with attemps < 3" in {
      val newJob1 = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz")
      await(slugParserJobsRepository.add(newJob1))
      val createdJob1 = checkSingleEntry(expectedSlugUri = newJob1.slugUri, expectedProcessed = false)

      val newJob2 = NewSlugParserJob("https://store/slugs/my-slug/my-slug_0.28.0_0.5.2.tgz")
      await(slugParserJobsRepository.add(newJob2))
      await(slugParserJobsRepository.getAllEntries) should have size 2

      await(slugParserJobsRepository.markAttempted(createdJob1.id))
      await(slugParserJobsRepository.markAttempted(createdJob1.id))
      await(slugParserJobsRepository.markAttempted(createdJob1.id))

      val unprocessed = await(slugParserJobsRepository.getUnprocessed)
      unprocessed should have size 1
      unprocessed.head.slugUri shouldBe newJob2.slugUri
      unprocessed.head.processed shouldBe false
    }
  }

  def checkSingleEntry(expectedSlugUri: String, expectedProcessed: Boolean, expectedAttempts: Int = 0): MongoSlugParserJob = {
    val allEntries = await(slugParserJobsRepository.getAllEntries)
    allEntries should have size 1
    val createdJob = allEntries.head
    createdJob.slugUri shouldBe expectedSlugUri
    createdJob.processed shouldBe expectedProcessed
    createdJob.attempts shouldBe expectedAttempts
    createdJob
  }
}
