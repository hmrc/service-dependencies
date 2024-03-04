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
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.RepoType.Service
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository

import scala.concurrent.ExecutionContext.Implicits.global

class DerivedGroupArtefactRepositorySpec
  extends AnyWordSpecLike
     with Matchers
     with OptionValues
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[GroupArtefacts] {

  lazy val dependencyRepository  = new DerivedDependencyRepository(mongoComponent)
  lazy val deploymentRepository  = new DeploymentRepository(mongoComponent)
  lazy val derivedServiceDependenciesRepository =
    new DerivedServiceDependenciesRepository(
      mongoComponent,
      deploymentRepository
    )

  override def checkIndexedQueries = false

  override lazy val repository = new DerivedGroupArtefactRepository(mongoComponent, deploymentRepository)

  val compileDependency  = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"test.group.1:test.artefact.1:1.23.0\" -> \"test.group.1:test.artefact.1:1.23.0\" \n}"
  val provideDependency  = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"test.group.3:test.artefact.1:1.23.0\" -> \"test.group.3:test.artefact.1:1.23.0\" \n}"
  val testDependency     = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"test.group.1:test.artefact.3:1.23.0\" -> \"test.group.1:test.artefact.3:1.23.0\" \n}"

  val metaArtefactModule =
    MetaArtefactModule(
      name = "sub-module",
      group = "uk.gov.hmrc",
      sbtVersion = None,
      crossScalaVersions = None,
      publishSkip = Some(false),
      dependencyDotCompile = Some(compileDependency),
      dependencyDotProvided = Some(provideDependency),
      dependencyDotTest = Some(testDependency),
      dependencyDotIt = None
    )

  val metaArtefact =
    MetaArtefact(
      name = "repo5",
      version = Version("1.0.0"),
      uri = "",
      gitUrl = None,
      dependencyDotBuild = None,
      modules = Seq(metaArtefactModule)
    )

  "DerivedGroupArtefactRepository.findGroupsArtefacts" should {
    "return a map of artefact group to list of found artefacts" in {

      deploymentRepository.markLatest(metaArtefact.name, metaArtefact.version).futureValue

      derivedServiceDependenciesRepository.populateDependencies(metaArtefact).futureValue

      dependencyRepository.put(
        Seq(
          MetaArtefactDependency("repo1", Version("1.0.0"), List.empty, Service, "test.group.1", "test.artefact.1", Version("1.1.0"), compileFlag = true, providedFlag = true, testFlag = true, itFlag = true, buildFlag = true),
          MetaArtefactDependency("repo2", Version("1.0.0"), List.empty, Service, "test.group.1", "test.artefact.2", Version("1.1.0"), compileFlag = true, providedFlag = true, testFlag = true, itFlag = true, buildFlag = true),
          MetaArtefactDependency("repo3", Version("1.0.0"), List.empty, Service, "test.group.1", "test.artefact.1", Version("1.1.0"), compileFlag = true, providedFlag = true, testFlag = true, itFlag = true, buildFlag = true),
          MetaArtefactDependency("repo4", Version("1.0.0"), List.empty, Service, "test.group.2", "test.artefact.1", Version("1.1.0"), compileFlag = true, providedFlag = true, testFlag = true, itFlag = true, buildFlag = true)
        )
      ).futureValue

      repository.populateAll().futureValue

      val result = repository.findGroupsArtefacts().futureValue

      result should have size 3
      result shouldEqual List(
        GroupArtefacts("test.group.1", List("test.artefact.1", "test.artefact.2", "test.artefact.3")),
        GroupArtefacts("test.group.2", List("test.artefact.1")),
        GroupArtefacts("test.group.3", List("test.artefact.1"))
      )
    }
  }

}
