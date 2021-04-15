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

package uk.gov.hmrc.servicedependencies.persistence.derived

import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, ServiceDependency}
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos.slugInfo
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, SlugInfoRepository}
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class DerivedServiceDependenciesRepositorySpec
  extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with MockitoSugar
    // We don't mixin IndexedMongoQueriesSupport here, as this repo makes use of queries not satisfied by an index
    with PlayMongoRepositorySupport[ServiceDependency]
    with CleanMongoCollectionSupport {

  lazy val deploymentRepository  = new DeploymentRepository(mongoComponent)
  lazy val slugInfoRepo          = new SlugInfoRepository(mongoComponent, deploymentRepository)
  lazy val dependencyGraphParser = new DependencyGraphParser()
  override lazy val repository =
    new DerivedServiceDependenciesRepository(
      mongoComponent,
      dependencyGraphParser,
      deploymentRepository
    )

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)

  "DerivedServiceDependenciesRepository.populate" should {
    "populate dependencies from dependencyDot file" in {
      val slugWithDependencies = slugInfo.copy(dependencyDotCompile = scala.io.Source.fromResource("slugs/dependencies-compile.dot").mkString)
      slugInfoRepo.add(slugWithDependencies).futureValue

      repository.populate(Seq.empty).futureValue

      val result = repository.collection.find().toFuture.futureValue

      result should have size 4
      result shouldEqual List(
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = "0.27.0",
          teams        = List.empty,
          depGroup     = "com.typesafe.play",
          depArtefact  = "filters-helpers",
          depVersion   = "2.7.5",
          scalaVersion = Some("2.12"),
          scopes       = Set(DependencyScope.Compile)
        ),
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = "0.27.0",
          teams        = List.empty,
          depGroup     = "org.typelevel",
          depArtefact  = "cats-core",
          depVersion   = "2.2.0",
          scalaVersion = Some("2.12"),
          scopes       = Set(DependencyScope.Compile)
        ),
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = "0.27.0",
          teams        = List.empty,
          depGroup     = "org.typelevel",
          depArtefact  = "cats-kernel",
          depVersion   = "2.2.0",
          scalaVersion = Some("2.12"),
          scopes       = Set(DependencyScope.Compile)
        ),
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = "0.27.0",
          teams        = List.empty,
          depGroup     = "uk.gov.hmrc",
          depArtefact  = "file-upload",
          depVersion   = "2.22.0",
          scalaVersion = Some("2.12"),
          scopes       = Set(DependencyScope.Compile)
        )
      )
    }
  }
}
