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
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag.Latest
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos._

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JdkVersionRepositorySpec
    extends AnyWordSpecLike
       with Matchers
      with MockitoSugar
      with DefaultMongoCollectionSupport {

  lazy val slugInfoRepo  = new SlugInfoRepository(mongoComponent, throttleConfig)
  lazy val jdkVersionRepo = new JdkVersionRepository(mongoComponent, throttleConfig)

  override protected lazy val collectionName: String   = jdkVersionRepo.collectionName
  override protected lazy val indexes: Seq[IndexModel] = jdkVersionRepo.indexes

  "JdkVersionRepository.findJDKUsage" should {
    "find all the jdk version for a given environment" in {
      slugInfoRepo.add(slugInfo).futureValue

      val result = jdkVersionRepo.findJDKUsage(Latest).futureValue

      result.length       shouldBe 1
      result.head.name    shouldBe slugInfo.name
      result.head.version shouldBe slugInfo.java.version
      result.head.vendor  shouldBe slugInfo.java.vendor
      result.head.kind    shouldBe slugInfo.java.kind
    }

    "ignore non-java slugs" in {
      slugInfoRepo.add(slugInfo).futureValue
      slugInfoRepo.add(nonJavaSlugInfo).futureValue

      val result = jdkVersionRepo.findJDKUsage(Latest).futureValue

      result.length shouldBe 1
      result.head.name shouldBe "my-slug"
    }
  }

}
