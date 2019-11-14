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

import org.mockito.MockitoSugar
import org.mongodb.scala.model.IndexModel
import org.scalatest.{Matchers, WordSpecLike}
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos._

import scala.concurrent.ExecutionContext.Implicits.global

class SlugInfoRepositorySpec
    extends WordSpecLike
      with Matchers
      with MockitoSugar
      with DefaultMongoCollectionSupport {

  val slugInfoRepo = new SlugInfoRepository(mongoComponent)

  override protected val collectionName: String   = slugInfoRepo.collectionName
  override protected val indexes: Seq[IndexModel] = slugInfoRepo.indexes

  "SlugInfoRepository.add" should {
    "insert correctly" in {
      slugInfoRepo.add(slugInfo).futureValue
      slugInfoRepo.getAllEntries.futureValue shouldBe Seq(slugInfo)
    }

    "replace existing" in {
      slugInfoRepo.add(slugInfo).futureValue shouldBe true
      slugInfoRepo.getAllEntries.futureValue should have size 1

      val duplicate = slugInfo.copy(name = "my-slug-2")
      slugInfoRepo.add(duplicate).futureValue shouldBe true
      slugInfoRepo.getAllEntries.futureValue shouldBe Seq(duplicate)
    }
  }

  "SlugInfoRepository.clearAllData" should {
    "delete everything" in {
      slugInfoRepo.add(slugInfo).futureValue
      slugInfoRepo.getAllEntries.futureValue should have size 1

      slugInfoRepo.clearAllData.futureValue
      slugInfoRepo.getAllEntries.futureValue shouldBe Nil
    }
  }

}
