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

package uk.gov.hmrc.servicedependencies.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import uk.gov.hmrc.servicedependencies.model.{DependencyScope, MetaArtefact, MetaArtefactModule, Version}

import java.time.Instant

class DependencyGraphParserSpec
  extends AnyWordSpec
     with Matchers {

  import DependencyGraphParser.Node, DependencyScope._
  "DependencyGraphParser.parseMetaArtefact" should {
    val compileDependency  = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_1.00:1.23.0\" -> \"artefact:build_1.00:1.23.0\" \n}"
    val providedDependency = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_2.00:1.23.0\" -> \"artefact:build_2.00:1.23.0\" \n}"
    val testDependency     = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_3.00:1.23.0\" -> \"artefact:build_3.00:1.23.0\" \n}"
    val itDependency       = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_4.00:1.23.0\" -> \"artefact:build_4.00:1.23.0\" \n}"
    val buildDependency    = "digraph \"dependency-graph\" {\n    graph[rankdir=\"LR\"]\n    edge [\n        arrowtail=\"none\"\n    ]\n        \"artefact:build_5.00:1.23.0\" -> \"artefact:build_5.00:1.23.0\" \n}"

    val module1 = MetaArtefactModule(
      name                  = "sub-module-1",
      group                 = "uk.gov.hmrc",
      sbtVersion            = Some(Version("1.4.9")),
      crossScalaVersions    = Some(List(Version("2.12.14"))),
      publishSkip           = Some(false),
      dependencyDotCompile  = Some(compileDependency),
      dependencyDotProvided = Some(providedDependency),
      dependencyDotTest     = None,
      dependencyDotIt       = None
    )

    val module2 = module1.copy(
      name                  = "sub-module-2",
      dependencyDotCompile  = None,
      dependencyDotProvided = None,
      dependencyDotTest     = Some(testDependency),
      dependencyDotIt       = None
    )

    val module3 = module1.copy(
      name                  = "sub-module-3",
      dependencyDotCompile  = None,
      dependencyDotProvided = None,
      dependencyDotTest     = None,
      dependencyDotIt       = Some(itDependency)
    )

    val libraryMetaArtefact = MetaArtefact(
      name               = "library-repo",
      version            = Version("1.0.0"),
      uri                = "https://artefacts/metadata/some/repo-v1.0.0.meta.tgz",
      gitUrl             = Some("https://github.com/hmrc/repo.git"),
      dependencyDotBuild = Some(buildDependency),
      buildInfo          = Map.empty,
      modules            = Seq(module1, module2, module3),
      created            = Instant.parse("2007-12-03T10:15:30.00Z")
    )

    "parse meta artefact dependencies to map of node and scopes, when all dependencies are different" in {
      DependencyGraphParser.parseMetaArtefact(libraryMetaArtefact) shouldBe Map(
        Node("org.scala-lang:scala-library:2.12.14") -> Set(DependencyScope.Compile, Test),
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

      DependencyGraphParser.parseMetaArtefact(meta) shouldBe Map(
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

      DependencyGraphParser.parseMetaArtefact(meta) shouldBe Map.empty
    }
  }

  "DependencyGraphParser.parse" should {
    "return dependencies with evictions applied" in {
      val source = scala.io.Source.fromResource("graphs/dependencies-compile.dot")
      val graph = DependencyGraphParser.parse(source.mkString)
      graph.dependencies shouldBe List(
        Node("com.typesafe.play:filters-helpers_2.12:2.7.5"),
        Node("org.typelevel:cats-core_2.12:2.2.0"),
        Node("org.typelevel:cats-kernel_2.12:2.2.0"),
        Node("uk.gov.hmrc:my-slug_2.12:2.22.0")
      )
    }

    "return dependencies from maven generated files" in {
      val source = scala.io.Source.fromResource("graphs/dependencies-maven.dot")
      val graph = DependencyGraphParser.parse(source.mkString)
      graph.dependencies should contain allElementsOf(List(
        Node("com.google.guava:guava:jar:18.0:compile"),
        Node("com.zaxxer:HikariCP:jar:2.5.1:compile"),
        Node("javax.xml.stream:stax-api:jar:1.0-2:compile"),
        Node("org.apache.commons:commons-lang3:jar:3.7:compile"),
        Node("org.springframework.ws:spring-ws-core:jar:2.1.4.RELEASE:compile"),
        Node("org.springframework.ws:spring-ws-support:jar:2.1.4.RELEASE:compile"),
        Node("org.springframework.ws:spring-xml:jar:2.1.4.RELEASE:compile"),
        Node("org.springframework:spring-oxm:jar:3.2.15.RELEASE:compile"),
        Node("uk.gov.hmrc.jdc:emcs:war:3.226.0"),
        Node("uk.gov.hmrc.rehoming:event-auditing:jar:2.0.0:compile"),
        Node("uk.gov.hmrc.rehoming:rehoming-common:jar:7.41.0:compile"),
        Node("wsdl4j:wsdl4j:jar:1.6.1:compile"),
        Node("xerces:xercesImpl:jar:2.12.0:compile")
      ))
    }
  }

  "DependencyGraphParser.anyPathToRoot" should {
    "return path to root" in {
      val source = scala.io.Source.fromResource("graphs/dependencies-compile.dot")
      val graph = DependencyGraphParser.parse(source.mkString)
      graph.anyPathToRoot(Node("org.typelevel:cats-kernel_2.12:2.2.0")) shouldBe List(
        Node("org.typelevel:cats-kernel_2.12:2.2.0"),
        Node("org.typelevel:cats-core_2.12:2.2.0"),
        Node("uk.gov.hmrc:my-slug_2.12:2.22.0")
      )
    }

    "work with maven dependencies" in {
      val source = scala.io.Source.fromResource("graphs/dependencies-maven.dot")
      val graph = DependencyGraphParser.parse(source.mkString)
      graph.anyPathToRoot(Node("javax.xml.stream:stax-api:jar:1.0-2:compile")) shouldBe List(
        Node("javax.xml.stream:stax-api:jar:1.0-2:compile"),
        Node("org.springframework.ws:spring-xml:jar:2.1.4.RELEASE:compile"),
        Node("org.springframework.ws:spring-ws-support:jar:2.1.4.RELEASE:compile"),
        Node("uk.gov.hmrc.jdc:emcs:war:3.226.0")
      )
    }

    "work with emcs dependencies" in {
      val source = scala.io.Source.fromResource("graphs/dependencies-emcs.dot")
      val graph = DependencyGraphParser.parse(source.mkString)
      graph.dependencies.map(d => (d.group, d.artefact, d.version, d.scalaVersion)) should not be empty
    }

    "work with trailing spaces" in {
      // note the trailing spaces and tabs
      val input = "digraph \"uk.gov.hmrc.jdc:platops-example-classic-service:war:0.53.0\" { \n" +
        "\t\"uk.gov.hmrc.jdc:platops-example-classic-service:war:0.53.0\" -> \"uk.gov.hmrc.jdc:platops-example-classic-service-business:jar:0.53.0:compile\" ; \n" +
        " } "
      val graph = DependencyGraphParser.parse(input)
      graph.dependencies should contain allElementsOf(List(
        Node("uk.gov.hmrc.jdc:platops-example-classic-service:war:0.53.0"),
        Node("uk.gov.hmrc.jdc:platops-example-classic-service-business:jar:0.53.0:compile"),
      ))
    }

    // BDOG-1884 if this test is hanging, its because anyPathToRoot's cycle detection has broken
    "not get stuck in an infinite loop when parsing a cyclical graph" in {
      val source = scala.io.Source.fromResource("graphs/loop.dot") // baz -> bar , bar -> baz
      val graph = DependencyGraphParser.parse(source.mkString)
      val baz = graph.nodes.filter(_.artefact == "baz").head
      graph.anyPathToRoot(baz).head shouldBe Node("org:baz:3.0.0")
      val bar = graph.nodes.filter(_.artefact == "bar").head
      graph.anyPathToRoot(bar).head shouldBe Node("org:bar:2.0.0")
    }

    "return the shortest path if multiple" in {
      val source = scala.io.Source.fromResource("graphs/double-path.dot")
      val graph = DependencyGraphParser.parse(source.mkString)
      val sbtSettings = graph.nodes.filter(_.artefact == "sbt-settings").head
      graph.anyPathToRoot(sbtSettings) shouldBe Seq(
        Node("uk.gov.hmrc:sbt-settings:0.0.1"),
        Node("default:project:0.1.0-SNAPSHOT")
      )
    }

    "ignore evicted nodes" in {
      val source = scala.io.Source.fromResource("graphs/evicted-paths.dot")
      val graph = DependencyGraphParser.parse(source.mkString)
      val log4j = graph.nodes.filter(_.artefact == "log4j").head
      graph.anyPathToRoot(log4j) shouldBe Seq(
        Node("uk.gov.hmrc:log4j:1.0.0"),
        Node("uk.gov.hmrc:sbt-settings:0.0.2"),
        Node("uk.gov.hmrc:sbt-auto-build:3.0.0"),
        Node("default:project:0.1.0-SNAPSHOT")
      )
    }
  }

  "Node" should {
    "parse name without scalaVersion" in {
      val n = Node("default:project:0.1.0-SNAPSHOT")
      n.group shouldBe "default"
      n.artefact shouldBe "project"
      n.version shouldBe "0.1.0-SNAPSHOT"
      n.scalaVersion shouldBe None
    }

    "parse name with scalaVersion" in {
      val n = Node("org.scala-lang.modules:scala-xml_2.12:1.3.0")
      n.group shouldBe "org.scala-lang.modules"
      n.artefact shouldBe "scala-xml"
      n.version shouldBe "1.3.0"
      n.scalaVersion shouldBe Some("2.12")
    }

    "parse name with scalaVersion and underscores" in {
      val n = Node("org.test.modules:test_artefact_2.12:1.0.0")
      n.group shouldBe "org.test.modules"
      n.artefact shouldBe "test_artefact"
      n.version shouldBe "1.0.0"
      n.scalaVersion shouldBe Some("2.12")
    }

    "parse name with type" in {
      val n = Node("uk.gov.hmrc.jdc:emcs:war:3.226.0")
      n.group shouldBe "uk.gov.hmrc.jdc"
      n.artefact shouldBe "emcs"
      n.version shouldBe "3.226.0"
      n.scalaVersion shouldBe None
    }

    "parse name with scope" in {
      val n = Node("javax.xml.stream:stax-api:1.0-2:compile")
      n.group shouldBe "javax.xml.stream"
      n.artefact shouldBe "stax-api"
      n.version shouldBe "1.0-2"
      n.scalaVersion shouldBe None
    }

    "parse name with type and scope" in {
      val n = Node("javax.xml.stream:stax-api:jar:1.0-2:compile")
      n.group shouldBe "javax.xml.stream"
      n.artefact shouldBe "stax-api"
      n.version shouldBe "1.0-2"
      n.scalaVersion shouldBe None
    }

    "parse name with type and classifier and scope" in {
      val n = Node("io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.0.51.Final:compile")
      n.group shouldBe "io.netty"
      n.artefact shouldBe "netty-transport-native-epoll"
      n.version shouldBe "4.0.51.Final"
      n.scalaVersion shouldBe None
    }

    "parse name with type and classifier" in {
      val n = Node("io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.0.51.Final")
      n.group shouldBe "io.netty"
      n.artefact shouldBe "netty-transport-native-epoll"
      n.version shouldBe "4.0.51.Final"
      n.scalaVersion shouldBe None
    }

    "parse a version that does not start with a number" in {
      val n = Node("ir.middleware:middleware-utils:jar:J22_2.9:compile")
      n.group shouldBe "ir.middleware"
      n.artefact shouldBe "middleware-utils"
      n.version shouldBe "J22_2.9"
      n.scalaVersion shouldBe None
    }

    "parse alternative type" in {
      val n = Node("uk.gov.hmrc.jdc.rsa:framework-core:test-jar:tests:7.69.0:test")
      n.group shouldBe "uk.gov.hmrc.jdc.rsa"
      n.artefact shouldBe "framework-core"
      n.version shouldBe "7.69.0"
      n.scalaVersion shouldBe None
    }
  }

}
