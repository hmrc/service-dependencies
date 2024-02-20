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

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{Json, OWrites}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector, TeamsForServices}
import uk.gov.hmrc.servicedependencies.model.RepoType.{All, Other, Service, Test}
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag.Latest
import uk.gov.hmrc.servicedependencies.model.{BobbyVersionRange, LatestVersion, MetaArtefactDependency, Version}
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDependencyRepository, DerivedModuleRepository}
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

  val group    = "uk.gov.hmrc.mongo"
  val artefact = "hmrc-mongo-lib1"
  val version  = Version("1.0.0")

  private def serviceMetaArtefactDependency(artefactVersion: Version): MetaArtefactDependency =
    MetaArtefactDependency(
      slugName        = "slug-name",
      slugVersion     = Version("1.0.0"),
      teams           = List("team-name"),
      repoType        = Service,
      group           = "group",
      artefact        = "artefact",
      artefactVersion = artefactVersion,
      compileFlag     = true,
      providedFlag    = false,
      testFlag        = false,
      itFlag          = false,
      buildFlag       = false
  )

  private def otherMetaArtefactDependency(artefactVersion: Version = Version("1.0.0")): MetaArtefactDependency =
    MetaArtefactDependency(
      slugName        = "slug-name",
      slugVersion     = Version("1.0.0"),
      teams           = List("team-name"),
      repoType        = Test,
      group           = "group",
      artefact        = "artefact",
      artefactVersion = artefactVersion,
      compileFlag     = true,
      providedFlag    = false,
      testFlag        = false,
      itFlag          = false,
      buildFlag       = false
  )

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

  "metaArtefactDependencies" should {

    "get artefact dependencies for Services and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.getTeamsForServices(any())).thenReturn(
        Future.successful(TeamsForServices(Map("slug-name" -> Seq("team-name"))))
      )

      when(boot.mockDerivedDependencyRepository.findServicesByDeployment(any(), any(), any(), any())).thenReturn(
        Future.successful(Seq(
          serviceMetaArtefactDependency(Version("1.0.0")),
          serviceMetaArtefactDependency(Version("1.2.0"))
        ))
      )

      when(boot.mockDerivedDependencyRepository.findByOtherRepository(any(), any(), any())).thenReturn(
        Future.successful(Seq(otherMetaArtefactDependency()))
      )

      val result = boot.controller.metaArtefactDependencies(
        flag          = Latest,
        repoType      = Some(Service),
        group         = None,
        artefact      = None,
        versionRange  = None,
        scope         = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      implicit val madWrites: OWrites[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(
        serviceMetaArtefactDependency(Version("1.0.0")),
        serviceMetaArtefactDependency(Version("1.2.0"))
      ))
    }

    "get artefact dependencies for Services, filter by range and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.getTeamsForServices(any())).thenReturn(
        Future.successful(TeamsForServices(Map("slug-name" -> Seq("team-name"))))
      )

      when(boot.mockDerivedDependencyRepository.findServicesByDeployment(any(), any(), any(), any())).thenReturn(
        Future.successful(Seq(
          serviceMetaArtefactDependency(Version("1.0.0")),
          serviceMetaArtefactDependency(Version("1.2.0"))
        ))
      )

      when(boot.mockDerivedDependencyRepository.findByOtherRepository(any(), any(), any())).thenReturn(
        Future.successful(Seq(otherMetaArtefactDependency()))
      )

      val result = boot.controller.metaArtefactDependencies(
        flag          = Latest,
        repoType      = Some(Service),
        group         = None,
        artefact      = None,
        versionRange  = Some(BobbyVersionRange("[1.0.0,1.1.0]")),
        scope         = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      implicit val madWrites: OWrites[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(serviceMetaArtefactDependency(Version("1.0.0"))))
    }

    "get artefact dependencies for Other and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.getTeamsForServices(any())).thenReturn(
        Future.successful(TeamsForServices(Map("slug-name" -> Seq("team-name"))))
      )

      when(boot.mockDerivedDependencyRepository.findServicesByDeployment(any(), any(), any(), any())).thenReturn(
        Future.successful(Seq(
          serviceMetaArtefactDependency(Version("1.0.0")),
          serviceMetaArtefactDependency(Version("1.2.0"))
        ))
      )

      when(boot.mockDerivedDependencyRepository.findByOtherRepository(any(), any(), any())).thenReturn(
        Future.successful(Seq(
          otherMetaArtefactDependency(Version("2.0.0")),
          otherMetaArtefactDependency(Version("2.2.0"))
        ))
      )

      val result = boot.controller.metaArtefactDependencies(
        flag         = Latest,
        repoType     = Some(Other),
        group        = None,
        artefact     = None,
        versionRange = None,
        scope        = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      implicit val madWrites: OWrites[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(
        otherMetaArtefactDependency(Version("2.0.0")),
        otherMetaArtefactDependency(Version("2.2.0")))
      )
    }

    "get artefact dependencies for Other, filter by range and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.getTeamsForServices(any())).thenReturn(
        Future.successful(TeamsForServices(Map("slug-name" -> Seq("team-name"))))
      )

      when(boot.mockDerivedDependencyRepository.findServicesByDeployment(any(), any(), any(), any())).thenReturn(
        Future.successful(Seq(
          serviceMetaArtefactDependency(Version("1.0.0")),
          serviceMetaArtefactDependency(Version("1.2.0"))
        ))
      )

      when(boot.mockDerivedDependencyRepository.findByOtherRepository(any(), any(), any())).thenReturn(
        Future.successful(Seq(
          otherMetaArtefactDependency(Version("2.0.0")),
          otherMetaArtefactDependency(Version("2.2.0"))
        ))
      )

      val result = boot.controller.metaArtefactDependencies(
        flag          = Latest,
        repoType      = Some(Other),
        group         = None,
        artefact      = None,
        versionRange  = Some(BobbyVersionRange("[2.0.0,2.1.0]")),
        scope         = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      implicit val madWrites: OWrites[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(otherMetaArtefactDependency(Version("2.0.0"))))
    }

    "get artefact dependencies for all repo types and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.getTeamsForServices(any())).thenReturn(
        Future.successful(TeamsForServices(Map("slug-name" -> Seq("team-name"))))
      )

      when(boot.mockDerivedDependencyRepository.findServicesByDeployment(any(), any(), any(), any())).thenReturn(
        Future.successful(Seq(
          serviceMetaArtefactDependency(Version("1.0.0")),
          serviceMetaArtefactDependency(Version("1.2.0"))
        ))
      )

      when(boot.mockDerivedDependencyRepository.findByOtherRepository(any(), any(), any())).thenReturn(
        Future.successful(Seq(
          otherMetaArtefactDependency(Version("2.0.0")),
          otherMetaArtefactDependency(Version("2.2.0"))
        ))
      )

      val result = boot.controller.metaArtefactDependencies(
        flag          = Latest,
        repoType      = Some(All),
        group         = None,
        artefact      = None,
        versionRange  = None,
        scope         = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      implicit val madWrites: OWrites[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(
        serviceMetaArtefactDependency(Version("1.0.0")),
        serviceMetaArtefactDependency(Version("1.2.0")),
        otherMetaArtefactDependency(Version("2.0.0")),
        otherMetaArtefactDependency(Version("2.2.0")))
      )
    }

    "get artefact dependencies for all repo types, filter by range and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.getTeamsForServices(any())).thenReturn(
        Future.successful(TeamsForServices(Map("slug-name" -> Seq("team-name"))))
      )

      when(boot.mockDerivedDependencyRepository.findServicesByDeployment(any(), any(), any(), any())).thenReturn(
        Future.successful(Seq(
          serviceMetaArtefactDependency(Version("1.0.0")),
          serviceMetaArtefactDependency(Version("1.2.0"))
        ))
      )

      when(boot.mockDerivedDependencyRepository.findByOtherRepository(any(), any(), any())).thenReturn(
        Future.successful(Seq(
          otherMetaArtefactDependency(Version("2.0.0")),
          otherMetaArtefactDependency(Version("2.2.0"))
        ))
      )

      val result = boot.controller.metaArtefactDependencies(
        flag          = Latest,
        repoType      = Some(All),
        group         = None,
        artefact      = None,
        versionRange  = Some(BobbyVersionRange("[1.0.0,2.0.0]")),
        scope         = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      implicit val madWrites: OWrites[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(
        serviceMetaArtefactDependency(Version("1.0.0")),
        serviceMetaArtefactDependency(Version("1.2.0")),
        otherMetaArtefactDependency(Version("2.0.0")))
      )
    }
  }

  case class Boot(
      mockSlugInfoService             : SlugInfoService
    , mockCuratedLibrariesService     : CuratedLibrariesService
    , mockServiceConfigsConnector     : ServiceConfigsConnector
    , mockTeamDependencyService       : TeamDependencyService
    , mockMetaArtefactRepository      : MetaArtefactRepository
    , mockTeamsAndRepositories        : TeamsAndRepositoriesConnector
    , mockLatestVersionRepository     : LatestVersionRepository
    , mockDerivedDependencyRepository : DerivedDependencyRepository
    , mockDerivedModuleRepository     : DerivedModuleRepository
    , controller                      : ServiceDependenciesController
    )

  object Boot {
    def init: Boot = {
      val mockSlugInfoService               = mock[SlugInfoService]
      val mockCuratedLibrariesService       = mock[CuratedLibrariesService]
      val mockServiceConfigsConnector       = mock[ServiceConfigsConnector]
      val mockTeamDependencyService         = mock[TeamDependencyService]
      val mockMetaArtefactRepository        = mock[MetaArtefactRepository]
      val mockLatestVersionRepository       = mock[LatestVersionRepository]
      val mockDerivedModuleRepository       = mock[DerivedModuleRepository]
      val mockDerivedDependencyRepository   = mock[DerivedDependencyRepository]
      val mockTeamsAndRepositoryConnector   = mock[TeamsAndRepositoriesConnector]
      val controller = new ServiceDependenciesController(
        mockSlugInfoService,
        mockCuratedLibrariesService,
        mockServiceConfigsConnector,
        mockTeamDependencyService,
        mockMetaArtefactRepository,
        mockLatestVersionRepository,
        mockDerivedModuleRepository,
        mockDerivedDependencyRepository,
        mockTeamsAndRepositoryConnector,
        stubControllerComponents()
      )
      Boot(
        mockSlugInfoService,
        mockCuratedLibrariesService,
        mockServiceConfigsConnector,
        mockTeamDependencyService,
        mockMetaArtefactRepository,
        mockTeamsAndRepositoryConnector,
        mockLatestVersionRepository,
        mockDerivedDependencyRepository,
        mockDerivedModuleRepository,
        controller
      )
    }
  }
}
