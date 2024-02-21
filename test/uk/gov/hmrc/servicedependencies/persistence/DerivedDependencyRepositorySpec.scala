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
import uk.gov.hmrc.servicedependencies.model.DependencyScope.{Compile, Provided}
import uk.gov.hmrc.servicedependencies.model.RepoType.{Other, Service, Test}
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, MetaArtefactDependency, Version}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedDependencyRepository

import scala.concurrent.ExecutionContext.Implicits.global

class DerivedDependencyRepositorySpec
  extends AnyWordSpecLike
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[MetaArtefactDependency] {

  lazy val deploymentRepository = new DeploymentRepository(mongoComponent)

  override lazy val repository = new DerivedDependencyRepository(mongoComponent, deploymentRepository)

  private val metaArtefactDependency1 = MetaArtefactDependency(
    repoName        = "name-1",
    group           = "group-1",
    artefact        = "artifact-1",
    artefactVersion = Version("1.0.0"),
    compileFlag     = true,
    providedFlag    = false,
    testFlag        = false,
    itFlag          = false,
    buildFlag       = false,
    teams           = List.empty,
    repoVersion     = Version("2.0.0"),
    repoType        = Service
  )

  private val metaArtefactDependency2 = MetaArtefactDependency(
    repoName        = "name-2",
    group           = "group-2",
    artefact        = "artifact-2",
    artefactVersion = Version("1.0.0"),
    compileFlag     = false,
    providedFlag    = true,
    testFlag        = false,
    itFlag          = false,
    buildFlag       = false,
    teams           = List.empty,
    repoVersion     = Version("2.0.0"),
    repoType        = Other
  )

  private val metaArtefactDependency3 = MetaArtefactDependency(
    repoName        = "name-3",
    group           = "group-3",
    artefact        = "artifact-3",
    artefactVersion = Version("1.0.0"),
    compileFlag     = false,
    providedFlag    = false,
    testFlag        = true,
    itFlag          = false,
    buildFlag       = false,
    teams           = List.empty,
    repoVersion     = Version("2.0.0"),
    repoType        = Test
  )

  override def beforeEach(): Unit = {
    dropDatabase()
    super.beforeEach()
  }

  "put" should {
    "insert new documents" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(Some(metaArtefactDependency1.repoName), None, None).futureValue mustBe Seq(metaArtefactDependency1)
      repository.find(Some(metaArtefactDependency2.repoName), None, None).futureValue mustBe Seq(metaArtefactDependency2)
    }

    "replace old documents" in {

      val metaArtefactDependencyUpdate = MetaArtefactDependency(
        repoName        = "name-1",
        group           = "group-1",
        artefact        = "artifact-1",
        artefactVersion = Version("2.0.0"),
        compileFlag     = true,
        providedFlag    = true,
        testFlag        = true,
        itFlag          = true,
        buildFlag       = true,
        repoVersion     = Version("3.0.0"),
        teams           = List.empty,
        repoType        = Service
      )

      repository.put(Seq(metaArtefactDependency1)).futureValue
      repository.find(Some(metaArtefactDependency1.repoName), None, None).futureValue mustBe Seq(metaArtefactDependency1)
      repository.put(Seq(metaArtefactDependencyUpdate)).futureValue
      repository.find(Some(metaArtefactDependency1.repoName), None, None).futureValue mustBe Seq(metaArtefactDependencyUpdate)
    }
  }

  "find" should {

    "find document by slug name" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(Some("name-1")).futureValue mustBe Seq(metaArtefactDependency1)
    }

    "find document by repo type" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(repoType = Some(Service)).futureValue mustBe Seq(metaArtefactDependency1)
      repository.find(repoType = Some(Other)).futureValue mustBe Seq(metaArtefactDependency2)
    }

    "find document by group" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(group = Some("group-1")).futureValue mustBe Seq(metaArtefactDependency1)
      repository.find(group = Some("group-2")).futureValue mustBe Seq(metaArtefactDependency2)
    }

    "find document by artefact" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(artefact = Some("artifact-1")).futureValue mustBe Seq(metaArtefactDependency1)
      repository.find(artefact = Some("artifact-2")).futureValue mustBe Seq(metaArtefactDependency2)
    }

    "find document by scope" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.find(scopes = Some(List(Compile))).futureValue mustBe Seq(metaArtefactDependency1)
      repository.find(scopes = Some(List(Provided))).futureValue mustBe Seq(metaArtefactDependency2)
      repository.find(scopes = Some(List(Compile, Provided))).futureValue.sortBy(_.repoName) mustBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }
  }

  "findByOtherRepository" should {

    "find documents that are not service type" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2, metaArtefactDependency3)).futureValue
      repository.findByOtherRepository().futureValue mustBe Seq(metaArtefactDependency2, metaArtefactDependency3)
    }

    "find documents that are not service type by group" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2, metaArtefactDependency3)).futureValue
      repository.findByOtherRepository(group = Some("group-2")).futureValue mustBe Seq(metaArtefactDependency2)
    }

    "find documents that are not service type by artefact" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2, metaArtefactDependency3)).futureValue
      repository.findByOtherRepository(artefact = Some("artifact-3")).futureValue mustBe Seq(metaArtefactDependency3)
    }

    "find documents that are not service type by scope" in {
      repository.put(Seq(metaArtefactDependency1, metaArtefactDependency2, metaArtefactDependency3)).futureValue
      repository.findByOtherRepository(scopes = Some(List(Provided))).futureValue mustBe Seq(metaArtefactDependency2)
      repository.findByOtherRepository(scopes = Some(List(DependencyScope.Test))).futureValue mustBe Seq(metaArtefactDependency3)
      repository.findByOtherRepository(scopes = Some(List(Provided, DependencyScope.Test))).futureValue.sortBy(_.repoName) mustBe Seq(metaArtefactDependency2, metaArtefactDependency3)
    }
  }
}
