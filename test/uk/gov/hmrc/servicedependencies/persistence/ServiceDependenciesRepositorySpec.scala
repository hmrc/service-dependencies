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
import play.api.Configuration
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport
import uk.gov.hmrc.mongo.throttle.ThrottleConfig
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos._

import scala.concurrent.ExecutionContext.Implicits.global

class ServiceDependenciesRepositorySpec
    extends WordSpecLike
      with Matchers
      with MockitoSugar
      with DefaultMongoCollectionSupport {

  val throttleConfig = new ThrottleConfig(Configuration())
  val slugInfoRepo   = new SlugInfoRepository(mongoComponent, throttleConfig)
  val serviceDependenciesRepo = new ServiceDependenciesRepository(mongoComponent)

  override protected val collectionName: String   = serviceDependenciesRepo.collectionName
  override protected val indexes: Seq[IndexModel] = serviceDependenciesRepo.indexes

  "ServiceDependenciesRepository.findServices" should {

    "only search the latest slugs" in {
      slugInfoRepo.add(oldSlugInfo).futureValue
      slugInfoRepo.add(slugInfo).futureValue

      val result = serviceDependenciesRepo.findServices(SlugInfoFlag.Latest, "com.test.group",  "lib1").futureValue

      result.length shouldBe 1

      result.head.slugVersion shouldBe "0.27.0"
      result.head.depArtefact shouldBe "lib1"
      result.head.depGroup shouldBe "com.test.group"
      result.head.depVersion shouldBe "1.2.0"
      result.head.depSemanticVersion shouldBe Some(Version(1, 2, 0))

    }

    "find all slugs with a dependency matched by group and artifact" in {
      slugInfoRepo.add(oldSlugInfo).futureValue
      slugInfoRepo.add(slugInfo).futureValue
      slugInfoRepo.add(otherSlug).futureValue

      val result = serviceDependenciesRepo.findServices(SlugInfoFlag.Latest,  "com.test.group",  "lib2").futureValue
      result.length shouldBe 1

      result.head.slugVersion shouldBe "0.27.0"
      result.head.depArtefact shouldBe "lib2"
      result.head.depGroup shouldBe "com.test.group"
      result.head.depVersion shouldBe "0.66"
    }
  }

}
