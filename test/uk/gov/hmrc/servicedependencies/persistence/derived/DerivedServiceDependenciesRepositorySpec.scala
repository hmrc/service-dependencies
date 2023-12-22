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
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, ServiceDependency, Version}
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, SlugInfoRepository, TestSlugInfos}
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.ExecutionContext.Implicits.global

class DerivedServiceDependenciesRepositorySpec
  extends AnyWordSpecLike
     with Matchers
     with OptionValues
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[ServiceDependency] {

  lazy val deploymentRepository  = new DeploymentRepository(mongoComponent)
  lazy val slugInfoRepo          = new SlugInfoRepository(mongoComponent, deploymentRepository)
  lazy val dependencyGraphParser = new DependencyGraphParser()
  override lazy val repository =
    new DerivedServiceDependenciesRepository(
      mongoComponent,
      dependencyGraphParser,
      deploymentRepository
    )

  "DerivedServiceDependenciesRepository.populateDependencies" should {
    "populate dependencies from meta-artefact" in {
      repository.populateDependencies(
        TestSlugInfos.slugInfo
      , TestSlugInfos.metaArtefact.copy(modules = TestSlugInfos.metaArtefact.modules.map(_.copy(dependencyDotCompile = Some(scala.io.Source.fromResource("graphs/dependencies-compile.dot").mkString))))
      ).futureValue

      val result = repository.collection.find().toFuture().futureValue

      result should have size 5
      result should contain theSameElementsAs List(
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = Version("0.27.0"),
          teams        = List.empty,
          depGroup     = "com.typesafe.play",
          depArtefact  = "filters-helpers",
          depVersion   = Version("2.7.5"),
          scalaVersion = Some("2.12"),
          scopes       = Set(DependencyScope.Compile)
        ),
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = Version("0.27.0"),
          teams        = List.empty,
          depGroup     = "org.typelevel",
          depArtefact  = "cats-core",
          depVersion   = Version("2.2.0"),
          scalaVersion = Some("2.12"),
          scopes       = Set(DependencyScope.Compile)
        ),
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = Version("0.27.0"),
          teams        = List.empty,
          depGroup     = "org.typelevel",
          depArtefact  = "cats-kernel",
          depVersion   = Version("2.2.0"),
          scalaVersion = Some("2.12"),
          scopes       = Set(DependencyScope.Compile)
        ),
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = Version("0.27.0"),
          teams        = List.empty,
          depGroup     = "org.scala-lang",
          depArtefact  = "scala-library",
          depVersion   = Version("2.12.14"),
          scalaVersion = None,
          scopes       = Set(DependencyScope.Compile, DependencyScope.Test)
        ),
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = Version("0.27.0"),
          teams        = List.empty,
          depGroup     = "org.scala-sbt",
          depArtefact  = "sbt",
          depVersion   = Version("1.4.9"),
          scalaVersion = None,
          scopes       = Set(DependencyScope.Build)
        )
      )
    }
  }
}
