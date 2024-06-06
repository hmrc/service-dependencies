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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.DependencyScope.{Compile, Provided}
import uk.gov.hmrc.servicedependencies.model.RepoType.Service
import uk.gov.hmrc.servicedependencies.model.{MetaArtefactDependency, Version}
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag

class DerivedDeployedDependencyRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[MetaArtefactDependency] {

  lazy val deploymentRepository =
    DeploymentRepository(mongoComponent)

  override val repository: DerivedDeployedDependencyRepository =
    DerivedDeployedDependencyRepository(mongoComponent, deploymentRepository)

  override def checkIndexedQueries = false

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
    repoType     = Service
  )

  private val metaArtefactDependency3 = MetaArtefactDependency(
    repoName     = "name-3",
    depGroup     = "group-3",
    depArtefact  = "artifact-3",
    depVersion   = Version("1.0.0"),
    compileFlag  = false,
    providedFlag = false,
    testFlag     = true,
    itFlag       = false,
    buildFlag    = false,
    teams        = List.empty,
    repoVersion  = Version("2.0.0"),
    repoType     = Service
  )

  override def beforeEach(): Unit = {
    dropDatabase()
    super.beforeEach()
  }

  "put" should {
    "insert new documents" in {
      deploymentRepository.setFlag(SlugInfoFlag.QA, metaArtefactDependency1.repoName, metaArtefactDependency1.repoVersion).futureValue
      deploymentRepository.setFlag(SlugInfoFlag.QA, metaArtefactDependency2.repoName, metaArtefactDependency2.repoVersion).futureValue
      deploymentRepository.setFlag(SlugInfoFlag.QA, metaArtefactDependency3.repoName, metaArtefactDependency3.repoVersion).futureValue

      repository.insert(List(metaArtefactDependency1, metaArtefactDependency2, metaArtefactDependency3)).futureValue
      repository.findWithDeploymentLookup(SlugInfoFlag.QA, Some("group-1"), Some("artifact-1"), None, None).futureValue.sortBy(_.repoName) shouldBe Seq(metaArtefactDependency1, metaArtefactDependency2)
      repository.findWithDeploymentLookup(SlugInfoFlag.QA, Some("group-3"), Some("artifact-3"), None, None).futureValue                    shouldBe Seq(metaArtefactDependency3)
    }
  }

  "findWithDeploymentLookup" should {
    "find document in QA & Production" in {
      deploymentRepository.setFlag(SlugInfoFlag.QA        , metaArtefactDependency1.repoName, metaArtefactDependency1.repoVersion).futureValue
      deploymentRepository.setFlag(SlugInfoFlag.Production, metaArtefactDependency3.repoName, metaArtefactDependency3.repoVersion).futureValue

      repository.insert(List(metaArtefactDependency1, metaArtefactDependency3)).futureValue
      repository.findWithDeploymentLookup(SlugInfoFlag.QA        , group = Some("group-1"), artefact = Some("artifact-1")).futureValue shouldBe Seq(metaArtefactDependency1)
      repository.findWithDeploymentLookup(SlugInfoFlag.Production, group = Some("group-1"), artefact = Some("artifact-1")).futureValue shouldBe Seq.empty
      repository.findWithDeploymentLookup(SlugInfoFlag.QA        , group = Some("group-3"), artefact = Some("artifact-3")).futureValue shouldBe Seq.empty
      repository.findWithDeploymentLookup(SlugInfoFlag.Production, group = Some("group-3"), artefact = Some("artifact-3")).futureValue shouldBe Seq(metaArtefactDependency3)
    }

    "find document by group, artefact & scope" in {
      deploymentRepository.setFlag(SlugInfoFlag.QA, metaArtefactDependency1.repoName, metaArtefactDependency1.repoVersion).futureValue
      deploymentRepository.setFlag(SlugInfoFlag.QA, metaArtefactDependency2.repoName, metaArtefactDependency2.repoVersion).futureValue

      repository.insert(List(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.findWithDeploymentLookup(SlugInfoFlag.QA, scopes = Some(List(Compile))          , group = Some("group-1"), artefact = Some("artifact-1")).futureValue                    shouldBe Seq(metaArtefactDependency1)
      repository.findWithDeploymentLookup(SlugInfoFlag.QA, scopes = Some(List(Provided))         , group = Some("group-1"), artefact = Some("artifact-1")).futureValue                    shouldBe Seq(metaArtefactDependency2)
      repository.findWithDeploymentLookup(SlugInfoFlag.QA, scopes = Some(List(Compile, Provided)), group = Some("group-1"), artefact = Some("artifact-1")).futureValue.sortBy(_.repoName) shouldBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }

    "find document just by scope" in {
      deploymentRepository.setFlag(SlugInfoFlag.QA, metaArtefactDependency1.repoName, metaArtefactDependency1.repoVersion).futureValue
      deploymentRepository.setFlag(SlugInfoFlag.QA, metaArtefactDependency2.repoName, metaArtefactDependency2.repoVersion).futureValue

      repository.insert(List(metaArtefactDependency1, metaArtefactDependency2)).futureValue
      repository.findWithDeploymentLookup(SlugInfoFlag.QA, scopes = Some(List(Compile))          ).futureValue                    shouldBe Seq(metaArtefactDependency1)
      repository.findWithDeploymentLookup(SlugInfoFlag.QA, scopes = Some(List(Provided))         ).futureValue                    shouldBe Seq(metaArtefactDependency2)
      repository.findWithDeploymentLookup(SlugInfoFlag.QA, scopes = Some(List(Compile, Provided))).futureValue.sortBy(_.repoName) shouldBe Seq(metaArtefactDependency1, metaArtefactDependency2)
    }
  }
}
