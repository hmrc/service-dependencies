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

import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.servicedependencies.connector.model.BobbyVersionRange
import uk.gov.hmrc.servicedependencies.connector.{ServiceDeploymentsConnector, TeamsAndRepositoriesConnector, TeamsForServices}
import uk.gov.hmrc.servicedependencies.model.{ServiceDependency, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{DependencyConfigRepository, SlugInfoRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlugInfoServiceSpec
  extends UnitSpec
     with MockitoSugar {

  implicit val hc = HeaderCarrier()

  val group        = "group"
  val artefact     = "artefact"

  val v100 =
    ServiceDependency(
        slugName           = "service1"
      , slugVersion        = "v1"
      , teams              = List.empty
      , depGroup           = group
      , depArtefact        = artefact
      , depVersion         = "1.0.0"
      )

  val v200 =
    ServiceDependency(
        slugName           = "service1"
      , slugVersion        = "v1"
      , teams              = List.empty
      , depGroup           = group
      , depArtefact        = artefact
      , depVersion         = "2.0.0"
      )

  val v205 =
    ServiceDependency(
        slugName           = "service1"
      , slugVersion        = "v1"
      , teams              = List.empty
      , depGroup           = group
      , depArtefact        = artefact
      , depVersion         = "2.0.5"
      )

  "SlugInfoService.findServicesWithDependency" should {
    "filter results by version" in {

      val boot = Boot.init

      when(boot.mockedSlugInfoRepository.findServices(SlugInfoFlag.Latest, group, artefact))
        .thenReturn(Future(Seq(v100, v200, v205)))

      when(boot.mockedTeamsAndRepositoriesConnector.getTeamsForServices)
        .thenReturn(Future(TeamsForServices(Map.empty)))

      await(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("[1.0.1,)"))) shouldBe Seq(v200, v205)
      await(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("(,1.0.1]"))) shouldBe Seq(v100)
      await(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("[2.0.0]"))) shouldBe Seq(v200)
    }

    "include non-parseable versions" in {

      val boot = Boot.init

      val bad = v100.copy(depVersion  = "r938")

      when(boot.mockedSlugInfoRepository.findServices(SlugInfoFlag.Latest, group, artefact))
        .thenReturn(Future(Seq(v100, v200, v205, bad)))

      when(boot.mockedTeamsAndRepositoriesConnector.getTeamsForServices)
        .thenReturn(Future(TeamsForServices(Map.empty)))

      await(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("[1.0.1,)"))) shouldBe Seq(v200, v205, bad)
      await(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("(,1.0.1]"))) shouldBe Seq(v100, bad)
    }
  }

  case class Boot(
      mockedSlugInfoRepository           : SlugInfoRepository
    , mockedDependencyConfigRepository   : DependencyConfigRepository
    , mockedTeamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
    , mockedServiceDeploymentsConnector  : ServiceDeploymentsConnector
    , service                            : SlugInfoService
    )

  object Boot {
    def init: Boot = {
      val mockedSlugInfoRepository            = mock[SlugInfoRepository]
      val mockedDependencyConfigRepository    = mock[DependencyConfigRepository]
      val mockedTeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
      val mockedServiceDeploymentsConnector   = mock[ServiceDeploymentsConnector]

      val service = new SlugInfoService(
            mockedSlugInfoRepository
          , mockedDependencyConfigRepository
          , mockedTeamsAndRepositoriesConnector
          , mockedServiceDeploymentsConnector
          )
      Boot(
          mockedSlugInfoRepository
        , mockedDependencyConfigRepository
        , mockedTeamsAndRepositoriesConnector
        , mockedServiceDeploymentsConnector
        , service
        )
    }
  }
 }