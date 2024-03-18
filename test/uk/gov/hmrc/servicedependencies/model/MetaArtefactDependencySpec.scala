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

package uk.gov.hmrc.servicedependencies.model

import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.servicedependencies.model.RepoType.Service

import java.time.Instant

class MetaArtefactDependencySpec extends AnyWordSpec with Matchers {

  private val metaArtefactModule =
    MetaArtefactModule(
      name = "sub-module-1",
      group = "uk.gov.hmrc",
      sbtVersion = Some(Version("1.4.9")),
      crossScalaVersions = Some(List(Version("2.12.14"))),
      publishSkip = Some(false),
      dependencyDotCompile =  None,
      dependencyDotProvided = None,
      dependencyDotTest = None,
      dependencyDotIt = None
    )

  private val metaArtefact =
    MetaArtefact(
      name = "test-artefact",
      version = Version("1.0.0"),
      uri = "https://artefacts/metadata/library/library-v1.0.0.meta.tgz",
      gitUrl = Some("https://github.com/hmrc/library.git"),
      dependencyDotBuild = None,
      buildInfo = Map(
        "GIT_URL" -> "https://github.com/hmrc/library.git"
      ),
      modules = Seq(
        metaArtefactModule
      ),
      created = Instant.parse("2007-12-03T10:15:30.00Z")
    )

  "MetaArtefactDependency" should {

    // "parse MetaArtefact to MetaArtefactDependency when given 1 dependency" in {

    //   val compileDependency   = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_1.00:1.23.0\" -> \"artefact:build_1.00:1.23.0\" \n}"
    //   val testDependency      = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_1.00:1.23.0\" -> \"artefact:build_1.00:1.23.0\" \n}"

    //   val newModule = metaArtefactModule.copy(
    //     dependencyDotCompile = Some(compileDependency),
    //     dependencyDotTest = Some(testDependency)
    //   )

    //   val newArtefact = metaArtefact.copy(
    //     modules = Seq(newModule)
    //   )

    //   val expectedResult = MetaArtefactDependency(
    //     repoName = "test-artefact",
    //     repoVersion = Version("1.0.0"),
    //     depGroup = "artefact",
    //     depArtefact = "build",
    //     depVersion = Version("1.23.0"),
    //     compileFlag = true,
    //     providedFlag = false,
    //     testFlag = true,
    //     itFlag = false,
    //     buildFlag = false,
    //     teams = List.empty,
    //     repoType = Service
    //   )

    //   MetaArtefactDependency.fromMetaArtefact(newArtefact, Service) mustBe Seq(expectedResult)
    // }

    // "parse MetaArtefact to MetaArtefactDependency when given multiple dependencies" in {

    //   val compileDependency = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_1.00:1.23.0\" -> \"artefact:build_1.00:1.23.0\" \n}"
    //   val testDependency    = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_2.00:1.23.0\" -> \"artefact:build_2.00:1.23.0\" \n}"

    //   val newModule = metaArtefactModule.copy(
    //     dependencyDotCompile = Some(compileDependency),
    //     dependencyDotTest = Some(testDependency)
    //   )

    //   val newArtefact = metaArtefact.copy(
    //     modules = Seq(newModule)
    //   )

    //   val expectedResult1 = MetaArtefactDependency(
    //     repoName = "test-artefact",
    //     repoVersion = Version("1.0.0"),
    //     depGroup = "artefact",
    //     depArtefact = "build",
    //     depVersion = Version("1.23.0"),
    //     compileFlag = true,
    //     providedFlag = false,
    //     testFlag = false,
    //     itFlag = false,
    //     buildFlag = false,
    //     teams = List.empty,
    //     repoType = Service
    //   )

    //   val expectedResult2 = MetaArtefactDependency(
    //     repoName = "test-artefact",
    //     repoVersion = Version("1.0.0"),
    //     depGroup = "artefact",
    //     depArtefact = "build",
    //     depVersion = Version("1.23.0"),
    //     compileFlag = false,
    //     providedFlag = false,
    //     testFlag = true,
    //     itFlag = false,
    //     buildFlag = false,
    //     teams = List.empty,
    //     repoType = Service
    //   )

    //   MetaArtefactDependency.fromMetaArtefact(newArtefact, Service) mustBe Seq(expectedResult1, expectedResult2)
    // }

  }

}
