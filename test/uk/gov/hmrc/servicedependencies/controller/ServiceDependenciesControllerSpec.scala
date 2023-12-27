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

package uk.gov.hmrc.servicedependencies.controller

import org.mockito.MockitoSugar
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model.{LatestVersion, Version}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedModuleRepository
import uk.gov.hmrc.servicedependencies.service._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ServiceDependenciesControllerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  val group    = "uk.gov.hmrc.mongo"
  val artefact = "hmrc-mongo-lib1"
  val version  = Version("1.0.0")

  "repositoryName" should {
    "get repositoryName for a SlugInfoFlag" in {
      val boot = Boot.init
      when(boot.mockDerivedModuleRepository.findNameByModule(group, artefact, version))
        .thenReturn(Future.successful(Some("hmrc-mongo")))

      val result = boot.controller.repositoryName(group, artefact, version.toString).apply(FakeRequest())

      contentAsJson(result) shouldBe Json.parse(s""""hmrc-mongo"""")
    }

    "return Not Found when the requested repo is not recognised" in {
      val boot = Boot.init
       when(boot.mockDerivedModuleRepository.findNameByModule(group, artefact, version))
        .thenReturn(Future.successful(None))

      val result = boot.controller.repositoryName(group, artefact, version.toString).apply(FakeRequest())

      status(result) shouldBe NOT_FOUND
    }
  }

  "latestVersion" should {
    "find a dependency by group and artefact" in {
      val boot = Boot.init
      when(boot.mockLatestVersionRepository.find(group, artefact))
        .thenReturn(Future.successful(Some(LatestVersion(group = group, name = artefact, version = Version("0.1.0"), updateDate = java.time.Instant.now))))

      val result = boot.controller.latestVersion(group, artefact).apply(FakeRequest())

      contentAsJson(result) shouldBe Json.parse(s"""{"group":"$group","artefact":"$artefact","version":"0.1.0"}""")
    }

    "return Not Found when the requested repo is not recognised" in {
      val boot = Boot.init
       when(boot.mockLatestVersionRepository.find(group, artefact))
        .thenReturn(Future.successful(None))

      val result = boot.controller.latestVersion(group, artefact).apply(FakeRequest())

      status(result) shouldBe NOT_FOUND
    }
  }

  case class Boot(
      mockSlugInfoService        : SlugInfoService
    , mockCuratedLibrariesService: CuratedLibrariesService
    , mockServiceConfigsConnector: ServiceConfigsConnector
    , mockTeamDependencyService  : TeamDependencyService
    , mockMetaArtefactRepository : MetaArtefactRepository
    , mockLatestVersionRepository: LatestVersionRepository
    , mockDerivedModuleRepository: DerivedModuleRepository
    , controller                 : ServiceDependenciesController
    )

  object Boot {
    def init: Boot = {
      val mockSlugInfoService         = mock[SlugInfoService]
      val mockCuratedLibrariesService = mock[CuratedLibrariesService]
      val mockServiceConfigsConnector = mock[ServiceConfigsConnector]
      val mockTeamDependencyService   = mock[TeamDependencyService]
      val mockMetaArtefactRepository  = mock[MetaArtefactRepository]
      val mockLatestVersionRepository = mock[LatestVersionRepository]
      val mockDerivedModuleRepository = mock[DerivedModuleRepository]
      val controller = new ServiceDependenciesController(
          mockSlugInfoService
        , mockCuratedLibrariesService
        , mockServiceConfigsConnector
        , mockTeamDependencyService
        , mockMetaArtefactRepository
        , mockLatestVersionRepository
        , mockDerivedModuleRepository
        , stubControllerComponents()
        )
      Boot(
          mockSlugInfoService
        , mockCuratedLibrariesService
        , mockServiceConfigsConnector
        , mockTeamDependencyService
        , mockMetaArtefactRepository
        , mockLatestVersionRepository
        , mockDerivedModuleRepository
        , controller
        )
    }
  }
}
