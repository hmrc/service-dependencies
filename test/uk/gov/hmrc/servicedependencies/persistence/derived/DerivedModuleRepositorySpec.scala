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

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, MetaArtefactModule, Version}

import scala.concurrent.ExecutionContext.Implicits.global

class DerivedModuleSpec
  extends AnyWordSpec
     with Matchers
     with OptionValues
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[DerivedModule] {

  override val repository: DerivedModuleRepository =
    new DerivedModuleRepository(mongoComponent)

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
      version            = Version("1.0.0"),
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
      created            = java.time.Instant.parse("2007-12-03T10:15:30.00Z")
    )


  "DerivedModuleSpec.findRepoNameByModule" should {
    "find repo name" in {
      (for {
         _      <- repository.update(metaArtefact)
         name   <- repository.findNameByModule(
                     group    = "uk.gov.hmrc",
                     artefact = "sub-module",
                     version  = Version("1.0.0")
                   )
         _      =  name shouldBe Some("library")
       } yield ()
      ).futureValue
    }

    "return data for any version if no match" in {
      (for {
         _      <- repository.update(metaArtefact)
         name   <- repository.findNameByModule(
                     group    = "uk.gov.hmrc",
                     artefact = "sub-module",
                     version  = Version("0.0.1") // no match for this
                   )
         _      =  name shouldBe Some("library")
       } yield ()
      ).futureValue
    }

    "return none if no match" in {
      (for {
         name   <- repository.findNameByModule(
                     group    = "uk.gov.hmrc",
                     artefact = "sub-module",
                     version  = Version("0.0.1") // no match for this
                   )
         _      =  name shouldBe None
       } yield ()
      ).futureValue
    }
  }

}
