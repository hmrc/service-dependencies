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
import uk.gov.hmrc.servicedependencies.model.SBTVersion
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag.Latest

import scala.concurrent.ExecutionContext.Implicits.global

class SbtVersionRepositorySpec
  extends AnyWordSpec
    with Matchers
    with MockitoSugar
    with DefaultPlayMongoRepositorySupport[SBTVersion] {

  lazy val deploymentRepository =
    DeploymentRepository(mongoComponent)

  override protected val repository: SbtVersionRepository =
    SbtVersionRepository(mongoComponent, deploymentRepository)

  lazy val slugInfoRepo =
    SlugInfoRepository(mongoComponent, deploymentRepository)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    MongoUtils.ensureIndexes(deploymentRepository.collection, deploymentRepository.indexes, replaceIndexes = false)
      .futureValue
  }

  "SbtVersionRepository.findSBTUsage" should {
    "find all the sbt versions for a given environment" in {
      slugInfoRepo.add(TestSlugInfos.slugInfo).futureValue
      deploymentRepository.markLatest(TestSlugInfos.slugInfo.name, TestSlugInfos.slugInfo.version).futureValue

      val result = repository.findSBTUsage(Latest).futureValue

      result.length           shouldBe 1
      result.head.serviceName shouldBe TestSlugInfos.slugInfo.name
      result.head.version     shouldBe TestSlugInfos.slugInfo.sbtVersion.get
    }

    "ignore slugs with no sbtVersion" in {
      slugInfoRepo.add(TestSlugInfos.slugInfo).futureValue
      slugInfoRepo.add(TestSlugInfos.nonJavaSlugInfo).futureValue
      deploymentRepository.markLatest(TestSlugInfos.slugInfo.name, TestSlugInfos.slugInfo.version).futureValue
      deploymentRepository.markLatest(TestSlugInfos.nonJavaSlugInfo.name, TestSlugInfos.nonJavaSlugInfo.version).futureValue

      val result = repository.findSBTUsage(Latest).futureValue

      result.length           shouldBe 1
      result.head.serviceName shouldBe "my-slug"
      result.head.version     shouldBe "1.4.9"
    }
  }

}
