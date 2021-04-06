/*
 * Copyright 2021 HM Revenue & Customs
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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DependencyGraphParserSpec
  extends AnyWordSpec
     with Matchers {
  import DependencyGraphParser._

  val dependencyGraphParser = new DependencyGraphParser

  "DependencyGraphParser.parse" should {
    "return dependencies with evictions applied" in {
      val source = scala.io.Source.fromResource("slugs/dependencies-compile.dot")
      val graph = dependencyGraphParser.parse(source.getLines.toSeq)
      graph.dependencies shouldBe List(
        Node("com.typesafe.play:filters-helpers_2.12:2.7.5"),
        Node("org.typelevel:cats-core_2.12:2.2.0"),
        Node("org.typelevel:cats-kernel_2.12:2.2.0"),
        Node("uk.gov.hmrc:file-upload_2.12:2.22.0")
      )
    }
  }

  "DependencyGraphParser.pathToRoot" should {
    "return path to root" in {
      val source = scala.io.Source.fromResource("slugs/dependencies-compile.dot")
      val graph = dependencyGraphParser.parse(source.getLines.toSeq)
      graph.pathToRoot(Node("org.typelevel:cats-kernel_2.12:2.2.0")) shouldBe List(
        Node("org.typelevel:cats-kernel_2.12:2.2.0"),
        Node("org.typelevel:cats-core_2.12:2.2.0"),
        Node("uk.gov.hmrc:file-upload_2.12:2.22.0")
      )
    }
  }
}
