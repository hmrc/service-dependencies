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

package uk.gov.hmrc.servicedependencies.persistence

import java.time.Instant

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, MetaArtefactModule, Version}

import scala.concurrent.ExecutionContext.Implicits.global

class MetaArtefactRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[MetaArtefact] {

  override val repository: MetaArtefactRepository =
    MetaArtefactRepository(mongoComponent)

  val metaArtefactModule =
    MetaArtefactModule(
      name                  = "sub-module",
      group                 = "uk.gov.hmrc",
      sbtVersion            = Some(Version("1.4.9")),
      crossScalaVersions    = Some(List(Version("2.12.14"))),
      publishSkip           = Some(false),
      dependencyDotCompile  = Some("ddc-graph"),
      dependencyDotProvided = Some("ddp-graph"),
      dependencyDotTest     = Some("ddt-graph"),
      dependencyDotIt       = Some("ddt-graph-it")
    )

  val metaArtefact =
    MetaArtefact(
      name               = "library",
      version            = Version("0.1.0"),
      uri                = "https://artefacts/metadata/library/library-v1.0.0.meta.tgz",
      gitUrl             = Some("https://github.com/hmrc/library.git"),
      dependencyDotBuild = Some("ddb-graph"),
      buildInfo          = Map(
                             "GIT_URL" -> "https://github.com/hmrc/library.git"
                           ),
      modules            = Seq(
                             metaArtefactModule,
                             metaArtefactModule.copy(name = "sub-module2")
                           ),
      created            = Instant.parse("2007-12-03T10:15:30.00Z")
    )

  val updatedMetaArtefact = metaArtefact.copy(version = Version("0.2.0"), modules = Seq(metaArtefactModule.copy(name = "sub-module3")))

  "add" should {
    "add correctly" in {
      (for
         before <- repository.find(metaArtefact.name)
         _      =  before shouldBe None
         _      <- repository.put(metaArtefact)
         after  <- repository.find(metaArtefact.name)
         all    <- repository.findAllVersions(metaArtefact.name)
         _      =  after shouldBe Some(metaArtefact.copy(latest = true))
       yield ()
      ).futureValue
    }

    "upsert correctly" in {
      (for
         before  <- repository.find(metaArtefact.name)
         _       =  before shouldBe None
         _       <- repository.put(metaArtefact)
         after   <- repository.find(metaArtefact.name)
         _       =  after shouldBe Some(metaArtefact.copy(latest = true))
         _       <- repository.put(updatedMetaArtefact)
         updated <- repository.find(metaArtefact.name)
         _       =  updated shouldBe Some(updatedMetaArtefact.copy(latest = true))
       yield ()
     ).futureValue
    }
  }

  "find" should {
    "return the latest" in {
      (for
         _      <- repository.put(metaArtefact.copy(version = Version("2.0.0")))
         _      <- repository.put(metaArtefact)
         found  <- repository.find(metaArtefact.name)
         _      =  found shouldBe Some(metaArtefact.copy(version = Version("2.0.0"), latest = true))
       yield ()
      ).futureValue
    }
  }

  "findAllVersions" should {
    "return all versions" in {
      (for
        _     <- repository.put(metaArtefact.copy(version = Version("2.0.0")))
        _     <- repository.put(metaArtefact)
        found <- repository.findAllVersions(metaArtefact.name)
        _     =  found should contain theSameElementsAs Seq(metaArtefact, metaArtefact.copy(version = Version("2.0.0"), latest = true))
      yield ()
      ).futureValue
    }
  }

  "findLatestVersionAtDate" should {
    "return the latest versionat a given date" in {
      (for
        _ <- repository.put(metaArtefact)
        _ <- repository.put(metaArtefact.copy(version = Version("1.1.0"), created = Instant.parse("2022-02-04T17:46:18.588Z")))
        _ <- repository.put(metaArtefact.copy(version = Version("1.2.0"), created = Instant.parse("2022-03-04T17:46:18.588Z")))
        _ <- repository.put(metaArtefact.copy(version = Version("1.3.0"), created = Instant.parse("2022-04-04T17:46:18.588Z")))
        found <- repository.findLatestVersionAtDate(metaArtefact.name, Instant.parse("2022-03-10T17:46:18.588Z"))
        _ = found shouldBe Some(metaArtefact.copy(version = Version("1.2.0"), created = Instant.parse("2022-03-04T17:46:18.588Z")))
      yield ()
        ).futureValue
    }
  }
}
