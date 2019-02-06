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
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatest.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{FailOnUnindexedQueries, MongoConnector, MongoSpecSupport, RepositoryPreparation}
import uk.gov.hmrc.servicedependencies.model.{SlugInfo, SlugDependency, Version}

import scala.concurrent.ExecutionContext.Implicits.global

class SlugInfoRepositorySpec
    extends WordSpecLike
       with Matchers
       with MongoSpecSupport
       with BeforeAndAfterEach
       with MockitoSugar
       with FailOnUnindexedQueries
       with RepositoryPreparation {

  val reactiveMongoComponent = new ReactiveMongoComponent {
    override val mongoConnector = {
      val mc = mock[MongoConnector]
      when(mc.db).thenReturn(mongo)
      mc
    }
  }

  val slugInfoRepository = new SlugInfoRepository(reactiveMongoComponent)

  override def beforeEach() {
    prepare(slugInfoRepository)
  }

  "SlugInfoRepository.add" should {
    "inserts correctly" in {
      await(slugInfoRepository.add(slugInfo))
      await(slugInfoRepository.getAllEntries) shouldBe Seq(slugInfo)
    }

    "replace exising" in {
      await(slugInfoRepository.add(slugInfo)) shouldBe true
      await(slugInfoRepository.getAllEntries) should have size 1

      val duplicate = slugInfo.copy(name = "my-slug-2")
      await(slugInfoRepository.add(duplicate)) shouldBe true
      await(slugInfoRepository.getAllEntries) shouldBe Seq(duplicate)
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

  "SlugInfoRepository.findServices" should {

    "only search the latest slugs" in {
      await(slugInfoRepository.add(oldSlugInfo))
      await(slugInfoRepository.add(slugInfo))

      val result = await(slugInfoRepository.findServices( "com.test.group",  "lib1"))

      result.length shouldBe 1

      result.head.slugVersion shouldBe "0.27.0"
      result.head.depArtefact shouldBe "lib1"
      result.head.depGroup shouldBe "com.test.group"
      result.head.depVersion shouldBe "1.2.0"
      result.head.depSemanticVersion shouldBe Some(Version(1, 2, 0))

    }

    "find all slugs with a dependency matched by group and artifact" in {
      await(slugInfoRepository.add(oldSlugInfo))
      await(slugInfoRepository.add(slugInfo))
      await(slugInfoRepository.add(otherSlug))

      val result = await(slugInfoRepository.findServices( "com.test.group",  "lib2"))
      result.length shouldBe 1

      result.head.slugVersion shouldBe "0.27.0"
      result.head.depArtefact shouldBe "lib2"
      result.head.depGroup shouldBe "com.test.group"
      result.head.depVersion shouldBe "0.66"
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
          version  = "1.2.0",
          group    = "com.test.group",
          artifact = "lib1"
        ),
        SlugDependency(
          path     = "lib2",
          version  = "0.66",
          group    = "com.test.group",
          artifact = "lib2")))

  val oldSlugInfo = slugInfo.copy(
    uri         = "https://store/slugs/my-slug/my-slug_0.26.0_0.5.2.tgz",
    version     = "0.26.0",
    versionLong = 26000
  )

  val otherSlug =   SlugInfo(
    uri             = "https://store/slugs/other-slug/other-slug_0.55.0_0.5.2.tgz",
    name            = "other-slug",
    version         = "0.55.0",
    versionLong     = 55000,
    runnerVersion   = "0.5.2",
    classpath       = "",
    jdkVersion      = "",
    dependencies    = List(
      SlugDependency(
        path     = "lib3",
        version  = "1.66.1",
        group    = "io.stuff",
        artifact = "lib3"
      )))
}
