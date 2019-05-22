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

package uk.gov.hmrc.servicedependencies.service

import org.scalatest.{FlatSpec, Matchers}
import uk.gov.hmrc.servicedependencies.controller.model.SlugFactory.aSlug
import uk.gov.hmrc.servicedependencies.model.Version

class SlugTransformSpec extends FlatSpec with Matchers {

  private val slugInfo = SlugTransform.createSlugInfo(aSlug)

  behavior of "createSlugInfo"

  it should "create SlugInfo with provided URI" in {
    slugInfo.uri shouldBe aSlug.uri
  }

  it should "create SlugInfo with provided name" in {
    slugInfo.name shouldBe aSlug.name
  }

  it should "create SlugInfo with provided version" in {
    slugInfo.version shouldBe Version(aSlug.version)
  }

  it should "create SlugInfo with provided teams" in {
    slugInfo.teams shouldBe aSlug.teams
  }

  it should "create SlugInfo with provided runnerVersion" in {
    slugInfo.runnerVersion shouldBe aSlug.runnerVersion
  }

  it should "create SlugInfo with provided classpath" in {
    slugInfo.classpath shouldBe aSlug.classpath
  }

  it should "create SlugInfo with provided jdkVersion" in {
    slugInfo.jdkVersion shouldBe aSlug.jdkVersion
  }

  it should "create SlugInfo with provided dependencies" in {
    slugInfo.dependencies shouldBe aSlug.dependencies
  }

  it should "create SlugInfo with provided applicationConfig" in {
    slugInfo.applicationConfig shouldBe aSlug.applicationConfig
  }

  it should "create SlugInfo with provided slugConfig" in {
    slugInfo.slugConfig shouldBe aSlug.slugConfig
  }

  it should "create SlugInfo with provided latest value" in {
    slugInfo.latest shouldBe aSlug.latest
  }
}