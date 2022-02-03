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

package uk.gov.hmrc.servicedependencies.controller

import org.mockito.MockitoSugar
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model.Version
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository}
import uk.gov.hmrc.servicedependencies.service._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ServiceDependenciesControllerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  "repositoryName" should {
    val group    = "uk.gov.hmrc.mongo"
    val artefact = "hmrc-mongo-lib1"
    val version  = Version("1.0.0")

    "get repositoryName for a SlugInfoFlag" in {
      val boot = Boot.init
      when(boot.mockMetaArtefactRepository.findRepoNameByModule(group, artefact, version))
        .thenReturn(Future.successful(Some("hmrc-mongo")))

      val result = boot.controller.repositoryName(group, artefact, version.toString).apply(FakeRequest())

      contentAsJson(result) shouldBe Json.parse(s""""hmrc-mongo"""")
    }

    "return Not Found when the requested repo is not recognised" in {
      val boot = Boot.init
       when(boot.mockMetaArtefactRepository.findRepoNameByModule(group, artefact, version))
        .thenReturn(Future.successful(None))

      val result = boot.controller.repositoryName(group, artefact, version.toString).apply(FakeRequest())

      status(result) shouldBe NOT_FOUND
    }
  }

  case class Boot(
      mockSlugInfoService              : SlugInfoService
    , mockSlugDependenciesService      : SlugDependenciesService
    , mockServiceConfigsConnector      : ServiceConfigsConnector
    , mockTeamDependencyService        : TeamDependencyService
    , mockRepositoryDependenciesService: RepositoryDependenciesService
    , mockMetaArtefactRepository       : MetaArtefactRepository
    , mockLatestVersionRepository      : LatestVersionRepository
    , controller                       : ServiceDependenciesController
    )

  object Boot {
    def init: Boot = {
      val mockSlugInfoService               = mock[SlugInfoService]
      val mockSlugDependenciesService       = mock[SlugDependenciesService]
      val mockServiceConfigsConnector       = mock[ServiceConfigsConnector]
      val mockTeamDependencyService         = mock[TeamDependencyService]
      val mockRepositoryDependenciesService = mock[RepositoryDependenciesService]
      val mockMetaArtefactRepository        = mock[MetaArtefactRepository]
      val mockLatestVersionRepository       = mock[LatestVersionRepository]
      val controller = new ServiceDependenciesController(
          mockSlugInfoService
        , mockSlugDependenciesService
        , mockServiceConfigsConnector
        , mockTeamDependencyService
        , mockRepositoryDependenciesService
        , mockMetaArtefactRepository
        , mockLatestVersionRepository
        , stubControllerComponents()
        )
      Boot(
          mockSlugInfoService
        , mockSlugDependenciesService
        , mockServiceConfigsConnector
        , mockTeamDependencyService
        , mockRepositoryDependenciesService
        , mockMetaArtefactRepository
        , mockLatestVersionRepository
        , controller
        )
    }
  }
}
