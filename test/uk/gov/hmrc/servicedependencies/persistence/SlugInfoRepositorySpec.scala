/*
 * Copyright 2023 HM Revenue & Customs
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

import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.SlugInfo
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global

class SlugInfoRepositorySpec
    extends AnyWordSpec
      with Matchers
      with MockitoSugar
      with DefaultPlayMongoRepositorySupport[SlugInfo] {

  lazy val deploymentRepository =
    DeploymentRepository(mongoComponent)

  override val repository: SlugInfoRepository =
    SlugInfoRepository(mongoComponent, deploymentRepository)

  "SlugInfoRepository.add" should {
    "insert correctly" in {
      repository.add(TestSlugInfos.slugInfo).futureValue
      repository.getAllEntries().futureValue shouldBe Seq(TestSlugInfos.slugInfo)
    }

    "replace existing" in {
      repository.add(TestSlugInfos.slugInfo).futureValue
      repository.getAllEntries().futureValue should have size 1

      val duplicate = TestSlugInfos.slugInfo.copy(name = "my-slug-2")
      repository.add(duplicate).futureValue
      repository.getAllEntries().futureValue shouldBe Seq(duplicate)
    }
  }

  "SlugInfoRepository.getUniqueSlugNames" should {
    "filter out duplicate names" in {
      repository.add(TestSlugInfos.slugInfo).futureValue
      repository.add(TestSlugInfos.otherSlug).futureValue
      repository.add(TestSlugInfos.otherSlug).futureValue
      repository.getUniqueSlugNames().futureValue shouldBe Seq("my-slug", "other-slug")
    }
  }
}
