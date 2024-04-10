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

import org.mongodb.scala.SingleObservableFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.DependencyScope.{Compile, Provided}
import uk.gov.hmrc.servicedependencies.model.RepoType.{Other, Service}
import uk.gov.hmrc.servicedependencies.model.{MetaArtefactDependency, Version}

import scala.concurrent.ExecutionContext.Implicits.global

class DerivedLatestDependencyRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[MetaArtefactDependency] {

  override val repository: DerivedLatestDependencyRepository =
    new DerivedLatestDependencyRepository(mongoComponent)

  private val metaArtefactDependency1 = MetaArtefactDependency(
    repoName     = "name-1"
  , depGroup     = "group-1"
  , depArtefact  = "artefact-1"
  , depVersion   = Version("1.0.0")
  , compileFlag  = true
  , providedFlag = false
  , testFlag     = false
  , itFlag       = false
  , buildFlag    = false
  , teams        = List.empty
  , repoVersion  = Version("2.0.0")
  , repoType     = Service
  )

  private val metaArtefactDependency2 = metaArtefactDependency1.copy(
    repoName     = "name-2",
    compileFlag  = false,
    providedFlag = true,
    repoType     = Other
  )

  override def beforeEach(): Unit = {
    dropDatabase()
    super.beforeEach()
  }

  "update" should {
    "add new documents" in {
      repository.update("name-1", List(metaArtefactDependency1)).futureValue
      repository.find(group = Some("group-1"), artefact = Some("artefact-1")).futureValue.sortBy(_.repoName) shouldBe Seq(metaArtefactDependency1)
    }

    "error if updating a different repoName" in {
      val exception = intercept[RuntimeException](repository.update("name-1", List(metaArtefactDependency2)).futureValue)
      exception shouldBe an[RuntimeException]
      exception.getMessage() shouldBe "Repo name: name-1 does not match dependencies name-2"
    }
  }

  "find" should {
    "find document by group, artefact & repo type" in {
      repository.collection.insertMany(List(metaArtefactDependency1, metaArtefactDependency2)).toFuture().futureValue
      repository.find(repoType = Some(List(Service))       , group = Some("group-1"), artefact = Some("artefact-1")).futureValue                    shouldBe Seq(metaArtefactDependency1)
      repository.find(repoType = Some(List(Other))         , group = Some("group-1"), artefact = Some("artefact-1")).futureValue                    shouldBe Seq(metaArtefactDependency2)
      repository.find(repoType = Some(List(Service, Other)), group = Some("group-1"), artefact = Some("artefact-1")).futureValue.sortBy(_.repoName) shouldBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }

    "find document by group, artefact & scope" in {
      repository.collection.insertMany(List(metaArtefactDependency1, metaArtefactDependency2)).toFuture().futureValue
      repository.find(scopes = Some(List(Compile))          , group = Some("group-1"), artefact = Some("artefact-1")).futureValue                    shouldBe Seq(metaArtefactDependency1)
      repository.find(scopes = Some(List(Provided))         , group = Some("group-1"), artefact = Some("artefact-1")).futureValue                    shouldBe Seq(metaArtefactDependency2)
      repository.find(scopes = Some(List(Compile, Provided)), group = Some("group-1"), artefact = Some("artefact-1")).futureValue.sortBy(_.repoName) shouldBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }

    "find document just by scope" in {
      repository.collection.insertMany(List(metaArtefactDependency1, metaArtefactDependency2)).toFuture().futureValue
      repository.find(scopes = Some(List(Compile))          ).futureValue                    shouldBe Seq(metaArtefactDependency1)
      repository.find(scopes = Some(List(Provided))         ).futureValue                    shouldBe Seq(metaArtefactDependency2)
      repository.find(scopes = Some(List(Compile, Provided))).futureValue.sortBy(_.repoName) shouldBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }
  }
}
