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

import org.mockito.MockitoSugar
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.{MetaArtefactDependency, Version}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedDependencyRepository

import scala.concurrent.ExecutionContext.Implicits.global

class DerivedDependencyRepositorySpec
  extends AnyWordSpecLike
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[MetaArtefactDependency] {

  override lazy val repository = new DerivedDependencyRepository(mongoComponent)

  private val metaArtefactDependency1 = MetaArtefactDependency(
    slugName = "name-1",
    group = "group-1",
    artefact = "artifact-1",
    artefactVersion = Version("1.0.0"),
    compileFlag = false,
    providedFlag = false,
    testFlag = false,
    itFlag = false,
    buildFlag = false,
    teams = List.empty,
    slugVersion = Version("2.0.0")
  )

  private val metaArtefactDependency2 = MetaArtefactDependency(
    slugName = "name-2",
    group = "group-2",
    artefact = "artifact-2",
    artefactVersion = Version("1.0.0"),
    compileFlag = false,
    providedFlag = false,
    testFlag = false,
    itFlag = false,
    buildFlag = false,
    teams = List.empty,
    slugVersion = Version("2.0.0")
  )

  "add" should {
    "insert new documents" in {
      repository.add(Seq(metaArtefactDependency1)).futureValue
      repository.find(Some(metaArtefactDependency1.slugName), None, None).futureValue mustBe Seq(metaArtefactDependency1)
    }
  }

  "addAndReplace" should {
    "insert new documents" in {
      repository.addAndReplace(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(Some(metaArtefactDependency1.slugName), None, None).futureValue mustBe Seq(metaArtefactDependency1)
      repository.find(Some(metaArtefactDependency2.slugName), None, None).futureValue mustBe Seq(metaArtefactDependency2)
    }

    "replace old documents" in {

      val metaArtefactDependencyUpdate = MetaArtefactDependency(
        slugName        = "name-1",
        group           = "group-1",
        artefact        = "artifact-1",
        artefactVersion = Version("2.0.0"),
        compileFlag     = true,
        providedFlag    = true,
        testFlag        = true,
        itFlag          = true,
        buildFlag       = true,
        slugVersion     = Version("3.0.0"),
        teams           = List.empty
      )

      repository.addAndReplace(Seq(metaArtefactDependency1)).futureValue
      repository.find(Some(metaArtefactDependency1.slugName), None, None).futureValue mustBe Seq(metaArtefactDependency1)
      repository.addAndReplace(Seq(metaArtefactDependencyUpdate)).futureValue
      repository.find(Some(metaArtefactDependency1.slugName), None, None).futureValue mustBe Seq(metaArtefactDependencyUpdate)
    }
  }
}
