/*
 * Copyright 2019 HM Revenue & Customs
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
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.model.{ServiceDependency, Version, VersionOp}
import uk.gov.hmrc.servicedependencies.persistence.{SlugInfoRepository, SlugParserJobsRepository}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class SlugInfoServiceSpec
  extends UnitSpec
     with MockitoSugar {

  val group    = "group"
  val artefact = "artefact"


  val v100 =
    ServiceDependency(
      slugName    = "service1",
      slugVersion = "v1",
      depGroup    = group,
      depArtefact = artefact,
      depVersion  = "1.0.0")
  val v200 =
    ServiceDependency(
      slugName    = "service1",
      slugVersion = "v1",
      depGroup    = group,
      depArtefact = artefact,
      depVersion  = "2.0.0")
  val v205 =
    ServiceDependency(
      slugName    = "service1",
      slugVersion = "v1",
      depGroup    = group,
      depArtefact = artefact,
      depVersion  = "2.0.5")

  "SlugInfoService.findServicesWithDependency" should {
    "filter results" in {

      val boot = Boot.init

      when(boot.mockedSlugInfoRepository.findServices(group, artefact))
        .thenReturn(Future(Seq(v100, v200, v205)))

      await(boot.service.findServicesWithDependency(group, artefact, versionOp = VersionOp.Gt, version = Version("1.0.1"))) shouldBe Seq(v200, v205)
      await(boot.service.findServicesWithDependency(group, artefact, versionOp = VersionOp.Lt, version = Version("1.0.1"))) shouldBe Seq(v100)
      await(boot.service.findServicesWithDependency(group, artefact, versionOp = VersionOp.Eq, version = Version("2.0.0"))) shouldBe Seq(v200)
    }

    "include non-parseable versions" in {

      val boot = Boot.init

      val bad = v100.copy(depVersion  = "20080701")

      when(boot.mockedSlugInfoRepository.findServices(group, artefact))
        .thenReturn(Future(Seq(v100, v200, v205, bad)))

      await(boot.service.findServicesWithDependency(group, artefact, versionOp = VersionOp.Gt, version = Version("1.0.1"))) shouldBe Seq(v200, v205, bad)
      await(boot.service.findServicesWithDependency(group, artefact, versionOp = VersionOp.Lt, version = Version("1.0.1"))) shouldBe Seq(v100, bad)
    }
  }

  case class Boot(
    mockedSlugParserJobsRepository: SlugParserJobsRepository,
    mockedSlugInfoRepository      : SlugInfoRepository,
    service                       : SlugInfoService)

  object Boot {
    def init: Boot = {
      val mockedSlugParserJobsRepository = mock[SlugParserJobsRepository]
      val mockedSlugInfoRepository       = mock[SlugInfoRepository]
      val slugInfoService = new SlugInfoService(mockedSlugParserJobsRepository, mockedSlugInfoRepository)
      Boot(
        mockedSlugParserJobsRepository,
        mockedSlugInfoRepository,
        slugInfoService)
    }
  }


  //   describe () {
  //   def findServicesWithDependency(
  //     group    : String,
  //     artefact : String,
  //     versionOp: VersionOp,
  //     version  : Version): Future[Seq[ServiceDependency]] =
  //   slugInfoRepository.findServices(group, artefact).map { l =>
  //     versionOp match {
  //       case VersionOp.Gt => l.filter(_.depSemanticVersion.map(_ >= version).getOrElse(true)) // include invalid semanticVersion in results
  //       case VersionOp.Lt => l.filter(_.depSemanticVersion.map(_ >= version).getOrElse(true))
  //       case VersionOp.Eq => l.filter(_.depSemanticVersion == Some(version))
  //     }
  //   }
  // }


}
