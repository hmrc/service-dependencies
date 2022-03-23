/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}
import uk.gov.hmrc.servicedependencies.model.{DependencyScope, MetaArtefact, MetaArtefactModule, ServiceDependency, SlugDependency, Version}
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos.slugInfo
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, SlugInfoRepository}
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

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

  "DerivedServiceDependenciesRepository.populateDependencies" should {
    "populate dependencies from dependencies" in {
      val slugWithDependencies = slugInfo.copy(dependencies =
        List(
          SlugDependency(
            path       = "./my-slug-0.27.0/lib/com.typesafe.play.filters-helpers-2.7.5.jar",
            version    = Version("2.7.5"),
            group      = "com.typesafe.play",
            artifact   = "filters-helpers",
            meta       = ""
         ),
         SlugDependency(
            path       = "./my-slug-0.27.0/lib/org.typelevel.cats-core_2.12-2.2.0.jar",
            version    = Version("2.2.0"),
            group      = "org.typelevel",
            artifact   = "cats-core",
            meta       = ""
         )
        )
      )

      repository.populateDependencies(slugWithDependencies, meta = None).futureValue

      val result = repository.collection.find().toFuture().futureValue

      result should have size 2
      result shouldEqual List(
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = Version("0.27.0"),
          teams        = List.empty,
          depGroup     = "com.typesafe.play",
          depArtefact  = "filters-helpers",
          depVersion   = Version("2.7.5"),
          scalaVersion = None,
          scopes       = Set(DependencyScope.Compile)
        ),
        ServiceDependency(
          slugName     = "my-slug",
          slugVersion  = Version("0.27.0"),
          teams        = List.empty,
          depGroup     = "org.typelevel",
          depArtefact  = "cats-core",
          depVersion   = Version("2.2.0"),
          scalaVersion = None,
          scopes       = Set(DependencyScope.Compile)
        )
      )
    }

    "populate dependencies from slug dependencyDot file if no meta-artefact" in {
      val slugWithDependencies = slugInfo.copy(dependencyDotCompile = scala.io.Source.fromResource("slugs/dependencies-compile.dot").mkString)

      repository.populateDependencies(slugWithDependencies, meta = None).futureValue

      val result = repository.collection.find().toFuture().futureValue

      result should have size 4
      result shouldEqual List(
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
          depGroup     = "uk.gov.hmrc",
          depArtefact  = "file-upload",
          depVersion   = Version("2.22.0"),
          scalaVersion = Some("2.12"),
          scopes       = Set(DependencyScope.Compile)
        )
      )
    }

    "populate dependencies from meta-artefact" in {
      val metaArtefactModule =
        MetaArtefactModule(
          name                 = "service",
          group                = "uk.gov.hmrc",
          sbtVersion           = Some(Version("1.4.9")),
          crossScalaVersions   = Some(List(Version("2.12.14"))),
          publishSkip          = Some(false),
          dependencyDotCompile = Some(scala.io.Source.fromResource("slugs/dependencies-compile.dot").mkString),
          dependencyDotTest    = Some("ddt-graph")
        )

      val metaArtefact =
        MetaArtefact(
          name               = "service",
          version            = Version("1.0.0"),
          uri                = "https://artefacts/metadata/service/service-1.0.0.meta.tgz",
          gitUrl             = Some("https://github.com/hmrc/service.git"),
          dependencyDotBuild = Some("ddb-graph"),
          buildInfo          = Map(
                                 "GIT_URL" -> "https://github.com/hmrc/service.git"
                               ),
          modules            = Seq(metaArtefactModule),
          created            = Instant.now()
        )


      repository.populateDependencies(slugInfo, meta = Some(metaArtefact)).futureValue

      val result = repository.collection.find().toFuture().futureValue

      result should have size 6
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
          depGroup     = "uk.gov.hmrc",
          depArtefact  = "file-upload",
          depVersion   = Version("2.22.0"),
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
