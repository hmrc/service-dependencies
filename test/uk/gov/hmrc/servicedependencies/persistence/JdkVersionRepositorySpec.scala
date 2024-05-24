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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.MongoUtils
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.JDKVersion
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag.Latest

import scala.concurrent.ExecutionContext.Implicits.global

class JdkVersionRepositorySpec
  extends AnyWordSpec
    with Matchers
    with MockitoSugar
    with DefaultPlayMongoRepositorySupport[JDKVersion] {

  lazy val deploymentRepository = DeploymentRepository(mongoComponent)

  override protected val repository: JdkVersionRepository =
    JdkVersionRepository(mongoComponent, deploymentRepository)

  lazy val slugInfoRepo = SlugInfoRepository(mongoComponent, deploymentRepository)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    MongoUtils.ensureIndexes(deploymentRepository.collection, deploymentRepository.indexes, replaceIndexes = false)
      .futureValue
  }

  "JdkVersionRepository.findJDKUsage" should {
    "find all the jdk version for a given environment" in {
      slugInfoRepo.add(TestSlugInfos.slugInfo).futureValue
      deploymentRepository.markLatest(TestSlugInfos.slugInfo.name, TestSlugInfos.slugInfo.version).futureValue

      val result = repository.findJDKUsage(Latest).futureValue

      result.length       shouldBe 1
      result.head.name    shouldBe TestSlugInfos.slugInfo.name
      result.head.version shouldBe TestSlugInfos.slugInfo.java.version
      result.head.vendor  shouldBe TestSlugInfos.slugInfo.java.vendor
      result.head.kind    shouldBe TestSlugInfos.slugInfo.java.kind
    }

    "ignore non-java slugs" in {
      slugInfoRepo.add(TestSlugInfos.slugInfo).futureValue
      slugInfoRepo.add(TestSlugInfos.nonJavaSlugInfo).futureValue
      deploymentRepository.markLatest(TestSlugInfos.slugInfo.name, TestSlugInfos.slugInfo.version).futureValue
      deploymentRepository.markLatest(TestSlugInfos.nonJavaSlugInfo.name, TestSlugInfos.nonJavaSlugInfo.version).futureValue

      val result = repository.findJDKUsage(Latest).futureValue

      result.length shouldBe 1
      result.head.name shouldBe "my-slug"
    }
  }

}
