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
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.{JavaInfo, SlugInfo, Version}

import java.time.Instant
import scala.concurrent.ExecutionContext


class SlugVersionRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[Version] {

  import ExecutionContext.Implicits.global

  override val repository: SlugVersionRepository =
    SlugVersionRepository(mongoComponent)

  val deploymentRepository =
    DeploymentRepository(mongoComponent)

  val slugInfoRepository =
    SlugInfoRepository(mongoComponent, deploymentRepository)

  "SlugVersionRepository" should {
    "return the max version" in {
      slugInfoRepository.add(sampleSlugInfo(Version(1, 1, 0), "/my-slug/1.1.0")).futureValue
      slugInfoRepository.add(sampleSlugInfo(Version(1, 0, 0), "/my-slug/1.0.0")).futureValue
      slugInfoRepository.add(sampleSlugInfo(Version(1, 4, 1), "/my-slug/1.4.1")).futureValue
      slugInfoRepository.add(sampleSlugInfo(Version(1, 4, 0), "/my-slug/1.4.0")).futureValue
      repository.getMaxVersion("my-slug").futureValue shouldBe Some(Version(1, 4, 1))
    }

    "return no max version when no previous slugs exist" in {
      repository.getMaxVersion("non-existing-slug").futureValue shouldBe None
    }
  }

  def sampleSlugInfo(version: Version, uri: String): SlugInfo =
    SlugInfo(
      created               = Instant.parse("2019-06-28T11:51:23.000Z"),
      uri                   = uri,
      name                  = "my-slug",
      version               = version,
      teams                 = List.empty,
      runnerVersion         = "0.5.2",
      classpath             = "",
      java                  = JavaInfo("1.8.1", "OpenJDK", "JRE"),
      sbtVersion            = Some("1.4.9"),
      repoUrl               = Some("https://github.com/hmrc/test.git"),
      applicationConfig     = "",
      slugConfig            = ""
    )
}
