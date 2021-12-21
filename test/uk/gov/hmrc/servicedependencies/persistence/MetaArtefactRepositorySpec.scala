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

package uk.gov.hmrc.servicedependencies.persistence

import java.time.Instant

import org.mockito.MockitoSugar
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.test.{PlayMongoRepositorySupport, CleanMongoCollectionSupport}
import uk.gov.hmrc.servicedependencies.model.{MetaArtefact, MetaArtefactModule, Version}

import scala.concurrent.ExecutionContext.Implicits.global

class MetaArtefactRepositorySpec
  extends AnyWordSpecLike
     with Matchers
     with MockitoSugar
     // with DefaultPlayMongoRepositorySupport[MetaArtefact] { // no index for findRepoNameByModule
     with PlayMongoRepositorySupport[MetaArtefact]
     with CleanMongoCollectionSupport
     with IntegrationPatience {

  override lazy val repository = new MetaArtefactRepository(mongoComponent)

  val metaArtefactModule =
    MetaArtefactModule(
      name                 = "sub-module",
      group                = "uk.gov.hmrc",
      dependencyDotCompile = Some("ddc-graph"),
      dependencyDotTest    = Some("ddt-graph")
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
      created            = Instant.now()
    )

  "findRepoNameByModule" should {
    "find repo name" in {
      (for {
         _      <- repository.insert(metaArtefact)
         name   <- repository.findRepoNameByModule(
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
         _      <- repository.insert(metaArtefact)
         name   <- repository.findRepoNameByModule(
                     group    = "uk.gov.hmrc",
                     artefact = "sub-module",
                     version  = Version("0.0.1") // no match for this
                   )
         _      =  name shouldBe Some("library")
       } yield ()
      ).futureValue
    }
  }
}
