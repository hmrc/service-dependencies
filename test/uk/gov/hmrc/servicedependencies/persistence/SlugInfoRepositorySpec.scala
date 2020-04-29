/*
 * Copyright 2020 HM Revenue & Customs
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

import org.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.SlugInfo
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos._

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SlugInfoRepositorySpec
    extends AnyWordSpecLike
      with Matchers
      with MockitoSugar
      with DefaultPlayMongoRepositorySupport[SlugInfo] {

  override lazy val repository = new SlugInfoRepository(mongoComponent)

  "SlugInfoRepository.add" should {
    "insert correctly" in {
      repository.add(slugInfo).futureValue
      repository.getAllEntries.futureValue shouldBe Seq(slugInfo)
    }

    "replace existing" in {
      repository.add(slugInfo).futureValue shouldBe true
      repository.getAllEntries.futureValue should have size 1

      val duplicate = slugInfo.copy(name = "my-slug-2")
      repository.add(duplicate).futureValue shouldBe true
      repository.getAllEntries.futureValue shouldBe Seq(duplicate)
    }
  }

  "SlugInfoRepository.clearAllData" should {
    "delete everything" in {
      repository.add(slugInfo).futureValue
      repository.getAllEntries.futureValue should have size 1

      repository.clearAllData.futureValue
      repository.getAllEntries.futureValue shouldBe Nil
    }
  }

  "SlugInfoRepository.getUniqueSlugNames" should {
    "filter out duplicate names" in {
      repository.add(slugInfo).futureValue
      repository.add(otherSlug).futureValue
      repository.add(otherSlug).futureValue
      repository.getUniqueSlugNames.futureValue shouldBe Seq("my-slug", "other-slug")
    }
  }
}
