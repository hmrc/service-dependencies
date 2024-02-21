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
import uk.gov.hmrc.servicedependencies.model.RepoType.{Library, Other, Service}
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, MetaArtefactDependency, MetaArtefactModule, Version}
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedDependencyRepository
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

  private val mockArtifactRepository: MetaArtefactRepository = mock[MetaArtefactRepository]
  private val mockDependencyRepository: DerivedDependencyRepository = mock[DerivedDependencyRepository]
  private val mockTeamsAndRepositoriesConnector: TeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]

  private val service = new DependencyService(mockArtifactRepository, mockDependencyRepository, mockTeamsAndRepositoriesConnector)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockArtifactRepository)
    reset(mockDependencyRepository)
  }

  val compileDependency  = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_1.00:1.23.0\" -> \"artefact:build_1.00:1.23.0\" \n}"
  val providedDependency = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_2.00:1.23.0\" -> \"artefact:build_2.00:1.23.0\" \n}"
  val testDependency     = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_3.00:1.23.0\" -> \"artefact:build_3.00:1.23.0\" \n}"
  val itDependency       = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_4.00:1.23.0\" -> \"artefact:build_4.00:1.23.0\" \n}"
  val buildDependency    = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_5.00:1.23.0\" -> \"artefact:build_5.00:1.23.0\" \n}"

  private val repository = Repository(
    "library-repo",
    Instant.now(),
    Seq.empty,
    Library
  )

  private val metaArtefactModule =
    MetaArtefactModule(
      name = "sub-module",
      group = "uk.gov.hmrc",
      sbtVersion = Some(Version("1.4.9")),
      crossScalaVersions = Some(List(Version("2.12.14"))),
      publishSkip = Some(false),
      dependencyDotCompile = Some(compileDependency),
      dependencyDotProvided = Some(providedDependency),
      dependencyDotTest = Some(testDependency),
      dependencyDotIt = Some(itDependency)
    )

  private val metaArtefact =
    MetaArtefact(
      name = "library-repo",
      version = Version("1.0.0"),
      uri = "https://artefacts/metadata/library/library-v1.0.0.meta.tgz",
      gitUrl = Some("https://github.com/hmrc/library.git"),
      dependencyDotBuild = Some(buildDependency),
      buildInfo = Map(
        "GIT_URL" -> "https://github.com/hmrc/library.git"
      ),
      modules = Seq(
        metaArtefactModule
      ),
      created = Instant.parse("2007-12-03T10:15:30.00Z")
    )

  "parseArtefactDependencies" should {

    "parse meta artefact dependencies to map of node and scopes, when all dependencies are different" in {

      val expectedResult = Map(
        Node("artefact:build_1.00:1.23.0") -> Set(Compile),
        Node("artefact:build_2.00:1.23.0") -> Set(Provided),
        Node("artefact:build_3.00:1.23.0") -> Set(Test),
        Node("artefact:build_4.00:1.23.0") -> Set(It),
        Node("artefact:build_5.00:1.23.0") -> Set(Build),
      )

      DependencyService.parseArtefactDependencies(metaArtefact) shouldBe expectedResult
    }

    "parse meta artefact dependencies to map of node and scopes, when dependencies are same across scopes" in {

      val crossScopeDependency  = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_1.00:1.23.0\" -> \"artefact:build_1.00:1.23.0\" \n}"

      val newModule = metaArtefactModule.copy(
        dependencyDotCompile = Some(crossScopeDependency),
        dependencyDotProvided = Some(crossScopeDependency),
        dependencyDotTest = Some(crossScopeDependency),
        dependencyDotIt = Some(crossScopeDependency)
      )

      val newArtefact = metaArtefact.copy(
        dependencyDotBuild = Some(crossScopeDependency),
        modules = Seq(newModule)
      )

      val expectedResult = Map(
        Node("artefact:build_1.00:1.23.0") -> Set(Compile, Provided, Test, It, Build)
      )

      DependencyService.parseArtefactDependencies(newArtefact) shouldBe expectedResult
    }

    "parse meta artefact dependencies to empty Map if non are found" in {

      val newModule = metaArtefactModule.copy(
        dependencyDotCompile = None,
        dependencyDotProvided = None,
        dependencyDotTest = None,
        dependencyDotIt = None
      )

      val newArtefact = metaArtefact.copy(
        dependencyDotBuild = None,
        modules = Seq(newModule)
      )

      DependencyService.parseArtefactDependencies(newArtefact) shouldBe Map.empty
    }
  }

  "setArtefactDependencies" should {

//    private def isLatest(metaArtefact: MetaArtefact): Future[Boolean] = {
//      metaArtefactRepository.find(metaArtefact.name).map {
//        _.map {
//          storedMeta => metaArtefact.version > storedMeta.version
//        }.isDefined
//      }
//    }

    "replace meta artefact when given a new version" in {

      when(mockArtifactRepository.find(any())).thenReturn(Future.successful(Some(metaArtefact)))
      when(mockDependencyRepository.put(any())).thenReturn(Future.successful(()))
      when(mockTeamsAndRepositoriesConnector.getRepository(any())(any())).thenReturn(Future.successful(Some(repository)))

      val newVersionArtefact: MetaArtefact = metaArtefact.copy(
        version = Version("1.1.0")
      )

      service.setArtefactDependencies(newVersionArtefact).futureValue mustBe ()

      verify(mockDependencyRepository, times(1))
        .put(MetaArtefactDependency.fromMetaArtefact(newVersionArtefact, Library))
    }

    "replace meta artefact when given a new version and set repo type to Other if it cannot be found" in {

      when(mockArtifactRepository.find(any())).thenReturn(Future.successful(Some(metaArtefact)))
      when(mockDependencyRepository.put(any())).thenReturn(Future.successful(()))
      when(mockTeamsAndRepositoriesConnector.getRepository(any())(any())).thenReturn(Future.successful(None))

      val newVersionArtefact: MetaArtefact = metaArtefact.copy(
        version = Version("1.1.0")
      )

      service.setArtefactDependencies(newVersionArtefact).futureValue mustBe()

      verify(mockDependencyRepository, times(1))
        .put(MetaArtefactDependency.fromMetaArtefact(newVersionArtefact, Other))
    }

    "not add meta artefact when given a old version" in {

      when(mockArtifactRepository.find(any())).thenReturn(Future.successful(Some(metaArtefact)))

      val oldVersionArtefact: MetaArtefact = metaArtefact.copy(
        version = Version("0.9.0")
      )

      service.setArtefactDependencies(oldVersionArtefact).futureValue mustBe()

      verifyNoMoreInteractions(mockDependencyRepository)
    }
  }

}
