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

package uk.gov.hmrc.servicedependencies.persistence.derived

import org.mockito.MockitoSugar
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.DependencyScope.{Compile, Provided}
import uk.gov.hmrc.servicedependencies.model.RepoType.{Other, Service, Test}
import uk.gov.hmrc.servicedependencies.model.{MetaArtefactDependency, Version}

import scala.concurrent.ExecutionContext.Implicits.global

class DerivedLatestDependencyRepositorySpec
  extends AnyWordSpecLike
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[MetaArtefactDependency] {

  override lazy val repository = new DerivedLatestDependencyRepository(mongoComponent)

  private val metaArtefactDependency1 = MetaArtefactDependency(
    repoName     = "name-1",
    depGroup     = "group-1",
    depArtefact  = "artifact-1",
    depVersion   = Version("1.0.0"),
    compileFlag  = true,
    providedFlag = false,
    testFlag     = false,
    itFlag       = false,
    buildFlag    = false,
    teams        = List.empty,
    repoVersion  = Version("2.0.0"),
    repoType     = Service
  )

  private val metaArtefactDependency2 = MetaArtefactDependency(
    repoName     = "name-2",
    depGroup     = "group-1",
    depArtefact  = "artifact-1",
    depVersion   = Version("1.0.0"),
    compileFlag  = false,
    providedFlag = true,
    testFlag     = false,
    itFlag       = false,
    buildFlag    = false,
    teams        = List.empty,
    repoVersion  = Version("2.0.0"),
    repoType     = Other
  )

  private val metaArtefactDependency3 = MetaArtefactDependency(
    repoName      = "name-3",
    depGroup      = "group-3",
    depArtefact   = "artifact-3",
    depVersion    = Version("1.0.0"),
    compileFlag   = false,
    providedFlag  = false,
    testFlag      = true,
    itFlag        = false,
    buildFlag     = false,
    teams         = List.empty,
    repoVersion   = Version("2.0.0"),
    repoType      = Test
  )

  override def beforeEach(): Unit = {
    dropDatabase()
    super.beforeEach()
  }

  "put" should {
    "insert new documents" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2, metaArtefactDependency3)).futureValue
      repository.find(Some("group-1"), Some("artifact-1"), None, None).futureValue.sortBy(_.repoName) mustBe Seq(metaArtefactDependency1, metaArtefactDependency2)
      repository.find(Some("group-3"), Some("artifact-3"), None, None).futureValue                    mustBe Seq(metaArtefactDependency3)
    }
  }

  "find" should {
    "find document by group, artefact & repo type" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(repoType = Some(List(Service))       , group = Some("group-1"), artefact = Some("artifact-1")).futureValue                    mustBe Seq(metaArtefactDependency1)
      repository.find(repoType = Some(List(Other))         , group = Some("group-1"), artefact = Some("artifact-1")).futureValue                    mustBe Seq(metaArtefactDependency2)
      repository.find(repoType = Some(List(Service, Other)), group = Some("group-1"), artefact = Some("artifact-1")).futureValue.sortBy(_.repoName) mustBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }

    "find document by group, artefact & scope" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(scopes = Some(List(Compile))          , group = Some("group-1"), artefact = Some("artifact-1")).futureValue                    mustBe Seq(metaArtefactDependency1)
      repository.find(scopes = Some(List(Provided))         , group = Some("group-1"), artefact = Some("artifact-1")).futureValue                    mustBe Seq(metaArtefactDependency2)
      repository.find(scopes = Some(List(Compile, Provided)), group = Some("group-1"), artefact = Some("artifact-1")).futureValue.sortBy(_.repoName) mustBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }

    "find document just by scope" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(scopes = Some(List(Compile))          ).futureValue                    mustBe Seq(metaArtefactDependency1)
      repository.find(scopes = Some(List(Provided))         ).futureValue                    mustBe Seq(metaArtefactDependency2)
      repository.find(scopes = Some(List(Compile, Provided))).futureValue.sortBy(_.repoName) mustBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }
  }
}
