/*
 * Copyright 2024 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.connector.model.Repository
import uk.gov.hmrc.servicedependencies.model.DependencyScope.{Build, Compile, It, Provided, Test}
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, MetaArtefactDependency, MetaArtefactModule, RepoType, Version}
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDependencyRepository, DerivedServiceDependenciesRepository}
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser.Node

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DependencyServiceSpec
    extends AnyWordSpec
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with IntegrationPatience
    with BeforeAndAfterEach {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val mockArtifactRepository: MetaArtefactRepository                        = mock[MetaArtefactRepository]
  private val mockDependencyRepository: DerivedDependencyRepository                 = mock[DerivedDependencyRepository]
  private val mockServiceDependencyRepository: DerivedServiceDependenciesRepository = mock[DerivedServiceDependenciesRepository]
  private val mockTeamsAndRepositoriesConnector: TeamsAndRepositoriesConnector      = mock[TeamsAndRepositoriesConnector]

  private val service = new DependencyService(mockArtifactRepository, mockDependencyRepository, mockServiceDependencyRepository, mockTeamsAndRepositoriesConnector)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockArtifactRepository)
    reset(mockDependencyRepository)
    reset(mockServiceDependencyRepository)
  }

  val compileDependency  = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_1.00:1.23.0\" -> \"artefact:build_1.00:1.23.0\" \n}"
  val providedDependency = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_2.00:1.23.0\" -> \"artefact:build_2.00:1.23.0\" \n}"
  val testDependency     = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_3.00:1.23.0\" -> \"artefact:build_3.00:1.23.0\" \n}"
  val itDependency       = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_4.00:1.23.0\" -> \"artefact:build_4.00:1.23.0\" \n}"
  val buildDependency    = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_5.00:1.23.0\" -> \"artefact:build_5.00:1.23.0\" \n}"

  private val serviceRepo = Repository(
    "service-repo",
    Instant.now(),
    Seq.empty,
    RepoType.Service
  )

  private val libraryRepo = serviceRepo.copy(
    name     = "library-repo",
    repoType = RepoType.Library
  )

  val module1: MetaArtefactModule = MetaArtefactModule(
    name = "sub-module-1",
    group = "uk.gov.hmrc",
    sbtVersion = Some(Version("1.4.9")),
    crossScalaVersions = Some(List(Version("2.12.14"))),
    publishSkip = Some(false),
    dependencyDotCompile = Some(compileDependency),
    dependencyDotProvided = Some(providedDependency),
    dependencyDotTest = None,
    dependencyDotIt = None
  )

  val module2: MetaArtefactModule = MetaArtefactModule(
    name = "sub-module-2",
    group = "uk.gov.hmrc",
    sbtVersion = Some(Version("1.4.9")),
    crossScalaVersions = Some(List(Version("2.12.14"))),
    publishSkip = Some(false),
    dependencyDotCompile = None,
    dependencyDotProvided = None,
    dependencyDotTest = Some(testDependency),
    dependencyDotIt = None
  )

  val module3: MetaArtefactModule = MetaArtefactModule(
    name = "sub-module-3",
    group = "uk.gov.hmrc",
    sbtVersion = Some(Version("1.4.9")),
    crossScalaVersions = Some(List(Version("2.12.14"))),
    publishSkip = Some(false),
    dependencyDotCompile = None,
    dependencyDotProvided = None,
    dependencyDotTest = None,
    dependencyDotIt = Some(itDependency)
  )

  private val serviceMetaArtefact =
    MetaArtefact(
      name = "service-repo",
      version = Version("1.0.0"),
      uri = "https://artefacts/metadata/some/repo-v1.0.0.meta.tgz",
      gitUrl = Some("https://github.com/hmrc/repo.git"),
      dependencyDotBuild = Some(buildDependency),
      buildInfo = Map.empty,
      modules = Seq(module1),
      created = Instant.parse("2007-12-03T10:15:30.00Z")
    )

  private val libraryMetaArtefact = serviceMetaArtefact.copy(
    name    = "library-repo",
    modules = Seq(module1, module2, module3),
  )

  "parseArtefactDependencies" should {

    "parse meta artefact dependencies to map of node and scopes, when all dependencies are different" in {
      DependencyService.parseMetaArtefact(libraryMetaArtefact) shouldBe Map(
        Node("org.scala-lang:scala-library:2.12.14") -> Set(Compile, Test),
        Node("org.scala-sbt:sbt:1.4.9")              -> Set(Build),
        Node("artefact:build_1.00:1.23.0")           -> Set(Compile),
        Node("artefact:build_2.00:1.23.0")           -> Set(Provided),
        Node("artefact:build_3.00:1.23.0")           -> Set(Test),
        Node("artefact:build_4.00:1.23.0")           -> Set(It),
        Node("artefact:build_5.00:1.23.0")           -> Set(Build),
      )
    }

    "parse meta artefact dependencies to map of node and scopes, when dependencies are same across scopes" in {
      val crossScopeDependency = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_1.00:1.23.0\" -> \"artefact:build_1.00:1.23.0\" \n}"
      val meta = libraryMetaArtefact.copy(
        dependencyDotBuild = Some(crossScopeDependency),
        modules            = Seq(
                               module1.copy(dependencyDotCompile = Some(crossScopeDependency), dependencyDotProvided = Some(crossScopeDependency)),
                               module2.copy(dependencyDotTest    = Some(crossScopeDependency)                                                    ),
                               module3.copy(dependencyDotIt      = Some(crossScopeDependency)                                                    )
                             )
      )

      DependencyService.parseMetaArtefact(meta) shouldBe Map(
        Node("org.scala-lang:scala-library:2.12.14") -> Set(Compile, Test),
        Node("org.scala-sbt:sbt:1.4.9")              -> Set(Build),
        Node("artefact:build_1.00:1.23.0")           -> Set(Compile, Provided, Test, It, Build)
      )
    }

    "parse meta artefact dependencies to empty Map if non are found" in {
      val meta = libraryMetaArtefact.copy(
        dependencyDotBuild = None,
        modules            = Seq(
                               module1.copy(dependencyDotCompile = None, dependencyDotProvided = None,  crossScalaVersions = None, sbtVersion = None),
                               module2.copy(dependencyDotTest    = None                              ,  crossScalaVersions = None, sbtVersion = None),
                               module3.copy(dependencyDotIt      = None                              ,  crossScalaVersions = None, sbtVersion = None)
                             )
      )

      DependencyService.parseMetaArtefact(meta) shouldBe Map.empty
    }
  }

  "setArtefactDependencies" should {

    "add service" in {
      when(mockArtifactRepository.find(any())).thenReturn(Future.successful(None))
      when(mockDependencyRepository.put(any())).thenReturn(Future.unit)
      when(mockServiceDependencyRepository.put(any())).thenReturn(Future.unit)
      when(mockTeamsAndRepositoriesConnector.getRepository(any())(any())).thenReturn(Future.successful(Some(serviceRepo)))

      val meta: MetaArtefact = serviceMetaArtefact.copy(version = Version("1.1.0"))

      service.addDependencies(meta).futureValue mustBe ()

      val deps =
        DependencyService
          .parseMetaArtefact(meta)
          .map { case (node, scopes) => MetaArtefactDependency.apply(meta, RepoType.Service, node, scopes) }
          .toSeq

      verify(mockDependencyRepository       , times(1)).put(deps)
      // verify(mockServiceDependencyRepository, times(1)).put(deps)
    }

    "replace meta artefact when given the same version" in {
      when(mockArtifactRepository.find(any())).thenReturn(Future.successful(Some(libraryMetaArtefact)))
      when(mockDependencyRepository.put(any())).thenReturn(Future.unit)
      when(mockTeamsAndRepositoriesConnector.getRepository(any())(any())).thenReturn(Future.successful(Some(libraryRepo)))

      val meta: MetaArtefact = libraryMetaArtefact.copy(version = Version("1.0.0"))

      service.addDependencies(meta).futureValue mustBe()

      val deps =
        DependencyService
          .parseMetaArtefact(meta)
          .map { case (node, scopes) => MetaArtefactDependency.apply(meta, RepoType.Library, node, scopes) }
          .toSeq

      verify(mockDependencyRepository, times(1)).put(deps)
    }

    "replace meta artefact when given a new version" in {
      when(mockArtifactRepository.find(any())).thenReturn(Future.successful(Some(libraryMetaArtefact)))
      when(mockDependencyRepository.put(any())).thenReturn(Future.unit)
      when(mockTeamsAndRepositoriesConnector.getRepository(any())(any())).thenReturn(Future.successful(Some(libraryRepo)))

      val meta: MetaArtefact = libraryMetaArtefact.copy(version = Version("1.1.0"))

      service.addDependencies(meta).futureValue mustBe ()

      val deps =
        DependencyService
          .parseMetaArtefact(meta)
          .map { case (node, scopes) => MetaArtefactDependency.apply(meta, RepoType.Library, node, scopes) }
          .toSeq

      verify(mockDependencyRepository, times(1)).put(deps)
    }

    "replace meta artefact when given a new version and set repo type to Other if it cannot be found" in {
      when(mockArtifactRepository.find(any())).thenReturn(Future.successful(Some(libraryMetaArtefact)))
      when(mockDependencyRepository.put(any())).thenReturn(Future.unit)
      when(mockTeamsAndRepositoriesConnector.getRepository(any())(any())).thenReturn(Future.successful(None))

      val meta: MetaArtefact = libraryMetaArtefact.copy(version = Version("1.1.0"))

      service.addDependencies(meta).futureValue mustBe ()

      val deps =
        DependencyService
          .parseMetaArtefact(meta)
          .map { case (node, scopes) => MetaArtefactDependency.apply(meta, RepoType.Other, node, scopes) }
          .toSeq

      verify(mockDependencyRepository, times(1)).put(deps)
    }

    "not add meta artefact when given a old version" in {
      when(mockArtifactRepository.find(any())).thenReturn(Future.successful(Some(libraryMetaArtefact)))

      val oldVersionArtefact: MetaArtefact = libraryMetaArtefact.copy(
        version = Version("0.9.0")
      )

      service.addDependencies(oldVersionArtefact).futureValue mustBe()

      verifyNoMoreInteractions(mockDependencyRepository)
    }
  }

}
