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
import org.mockito.Mockito.{verifyNoInteractions, when}
import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{Json, Writes}
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, TeamsAndRepositoriesConnector, VulnerabilitiesConnector}
import uk.gov.hmrc.servicedependencies.model.RepoType.Service
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag.{Development, Latest}
import uk.gov.hmrc.servicedependencies.model.*
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDeployedDependencyRepository, DerivedLatestDependencyRepository, DerivedModuleRepository}
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository}
import uk.gov.hmrc.servicedependencies.service.*

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ServiceDependenciesControllerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience
     with OptionValues {

  val group    = "uk.gov.hmrc.mongo"
  val artefact = "hmrc-mongo-lib1"
  val version  = Version("1.0.0")

  private def metaArtefactDependency(artefactVersion: Version): MetaArtefactDependency =
    MetaArtefactDependency(
      repoName        = "repo-name",
      repoVersion     = Version("1.0.0"),
      teams           = List("team-name"),
      digitalService  = None,
      repoType        = Service,
      depGroup        = "group",
      depArtefact     = "artefact",
      depVersion      = artefactVersion,
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

  "latestVersionAtDate" should {
    "find a dependency by group and artefact and return the latest version for a given date" in {
      val boot = Boot.init
      val metaArtefact =
        MetaArtefact(
          name               = "hmrc-mongo",
          version            = Version("1.0.0"),
          uri                = "https://store/meta/my-meta/hmrc-mongo-v1.0.0.meta.tgz",
          gitUrl             = Some("https://github.com/hmrc/hmrc-mongo.git"),
          dependencyDotBuild = Some("dependencyDotBuild"),
          buildInfo          = Map("k" -> "v"),
          modules            = Seq(MetaArtefactModule(
            name                  = artefact,
            group                 = group,
            sbtVersion            = Some(Version("1.4.9")),
            crossScalaVersions    = Some(List(Version("2.12.14"))),
            publishSkip           = Some(false),
            dependencyDotCompile  = Some("dependencyDotCompile"),
            dependencyDotProvided = Some("dependencyDotProvided"),
            dependencyDotTest     = Some("dependencyDotTest"),
            dependencyDotIt       = Some("dependencyDotIt")
          )),
          created = Instant.parse("2022-01-04T17:46:18.588Z")
      )
      when(boot.mockDerivedModuleRepository.findNameByModule(group, artefact))
        .thenReturn(Future.successful(Some("hmrc-mongo")))

      when(boot.mockMetaArtefactRepository.findLatestVersionAtDate("hmrc-mongo", Instant.parse("2022-01-10T17:46:18.588Z"), majorVersion = Some(1)))
        .thenReturn(Future.successful(Some(metaArtefact)))

      val result = boot.controller.latestVersionAtDate(group, artefact, Instant.parse("2022-01-10T17:46:18.588Z"), majorVersion = Some(1)).apply(FakeRequest())

      contentAsJson(result) shouldBe Json.parse(s"""{"group":"$group","artefact":"$artefact","version":"1.0.0"}""")
    }

    "return Not Found when no repo is found for the given module" in {
      val boot = Boot.init
      when(boot.mockDerivedModuleRepository.findNameByModule(group, artefact))
        .thenReturn(Future.successful(None))

      val result = boot.controller.latestVersionAtDate(group, artefact, Instant.parse("2022-03-10T17:46:18.588Z"), None).apply(FakeRequest())

      status(result) shouldBe NOT_FOUND
    }
  }

  "metaArtefactDependencies" should {
    "get slug info for services when flag is not Latest and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.cachedRepoMap()(using any[HeaderCarrier]))
        .thenReturn(
          Future.successful(Map("repo-name" -> (Seq("team-name"), None)))
        )

      when(boot.mockDerivedDeployedDependencyRepository.findWithDeploymentLookup(any(), any(), any(), any(), any(), any()))
        .thenReturn(
          Future.successful(Seq(
            metaArtefactDependency(Version("1.0.0")),
            metaArtefactDependency(Version("2.0.0")),
          ))
        )

      val result = boot.controller.metaArtefactDependencies(
        flag          = Development,
        group         = "group",
        artefact      = "artefact",
        repoType      = None,
        versionRange  = None,
        scope         = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      given Writes[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(
        metaArtefactDependency(Version("1.0.0")),
        metaArtefactDependency(Version("2.0.0"))
      ))

      verifyNoInteractions(boot.mockDerivedLatestDependencyRepository)
    }

    "get slug info for services when flag is not Latest, filter by range and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.cachedRepoMap()(using any[HeaderCarrier]))
        .thenReturn(
          Future.successful(Map("repo-name" -> (Seq("team-name"), None)))
        )

      when(boot.mockDerivedDeployedDependencyRepository.findWithDeploymentLookup(any(), any(), any(), any(), any(), any()))
        .thenReturn(
          Future.successful(Seq(
            metaArtefactDependency(Version("1.0.0")),
            metaArtefactDependency(Version("2.0.0")),
          ))
        )

      val result = boot.controller.metaArtefactDependencies(
        flag          = Development,
        group         = "group",
        artefact      = "artefact",
        repoType      = None,
        versionRange  = Some(BobbyVersionRange("[1.0.0,1.1.0]")),
        scope         = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      given Writes[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(
        metaArtefactDependency(Version("1.0.0"))
      ))

      verifyNoInteractions(boot.mockDerivedLatestDependencyRepository)
    }

    "get artefact dependencies when flag is Latest and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.cachedRepoMap()(using any[HeaderCarrier]))
        .thenReturn(
          Future.successful(Map("repo-name" -> (Seq("team-name"), None)))
        )

      when(boot.mockDerivedLatestDependencyRepository.find(any(), any(), any(), any(), any(), any()))
        .thenReturn(
          Future.successful(Seq(
            metaArtefactDependency(Version("1.0.0")),
            metaArtefactDependency(Version("2.0.0"))
          ))
        )

      val result = boot.controller.metaArtefactDependencies(
        repoType     = None,
        group        = "group",
        artefact     = "artefact",
        flag         = Latest,
        versionRange = None,
        scope        = None
      ).apply(FakeRequest())

      status(result) shouldBe OK

      given Writes[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(
        metaArtefactDependency(Version("1.0.0")),
        metaArtefactDependency(Version("2.0.0")))
      )

      verifyNoInteractions(boot.mockDerivedDeployedDependencyRepository)
    }

    "get artefact dependencies when flag is Latest, filter by range and set teams" in {
      val boot = Boot.init

      when(boot.mockTeamsAndRepositories.cachedRepoMap()(using any[HeaderCarrier]))
        .thenReturn(
          Future.successful(Map("repo-name" -> (Seq("team-name"), None)))
        )

      when(boot.mockDerivedLatestDependencyRepository.find(any(), any(), any(), any(), any(), any()))
        .thenReturn(
          Future.successful(Seq(
            metaArtefactDependency(Version("1.0.0")),
            metaArtefactDependency(Version("2.0.0"))
          ))
        )

      val result =
        boot.controller.metaArtefactDependencies(
          repoType     = None,
          group        = "group",
          artefact     = "artefact",
          flag         = Latest,
          versionRange = Some(BobbyVersionRange("[1.0.0,1.1.0]")),
          scope        = None
        ).apply(FakeRequest())

      status(result) shouldBe OK

      given Writes[MetaArtefactDependency] = MetaArtefactDependency.apiWrites

      contentAsJson(result) shouldBe Json.toJson(Seq(
        metaArtefactDependency(Version("1.0.0"))
      ))

      verifyNoInteractions(boot.mockDerivedDeployedDependencyRepository)
    }
  }

  case class Boot(
      mockSlugInfoService                    : SlugInfoService
    , mockCuratedLibrariesService            : CuratedLibrariesService
    , mockServiceConfigsConnector            : ServiceConfigsConnector
    , mockTeamDependencyService              : TeamDependencyService
    , mockMetaArtefactRepository             : MetaArtefactRepository
    , mockTeamsAndRepositories               : TeamsAndRepositoriesConnector
    , mockLatestVersionRepository            : LatestVersionRepository
    , mockDerivedDeployedDependencyRepository: DerivedDeployedDependencyRepository
    , mockDerivedLatestDependencyRepository  : DerivedLatestDependencyRepository
    , mockDerivedModuleRepository             : DerivedModuleRepository
    , controller                              : ServiceDependenciesController
    )

  object Boot {
    def init: Boot = {
      val mockSlugInfoService                     = mock[SlugInfoService]
      val mockCuratedLibrariesService             = mock[CuratedLibrariesService]
      val mockServiceConfigsConnector             = mock[ServiceConfigsConnector]
      val mockTeamDependencyService               = mock[TeamDependencyService]
      val mockMetaArtefactRepository              = mock[MetaArtefactRepository]
      val mockLatestVersionRepository             = mock[LatestVersionRepository]
      val mockDerivedModuleRepository             = mock[DerivedModuleRepository]
      val mockDerivedDeployedDependencyRepository = mock[DerivedDeployedDependencyRepository]
      val mockDerivedLatestDependencyRepository   = mock[DerivedLatestDependencyRepository]
      val mockTeamsAndRepositoryConnector         = mock[TeamsAndRepositoriesConnector]
      val mockVulnerabilitiesConnector            = mock[VulnerabilitiesConnector]
      val controller = ServiceDependenciesController(
        mockSlugInfoService,
        mockCuratedLibrariesService,
        mockServiceConfigsConnector,
        mockTeamDependencyService,
        mockMetaArtefactRepository,
        mockLatestVersionRepository,
        mockDerivedDeployedDependencyRepository,
        mockDerivedLatestDependencyRepository,
        mockDerivedModuleRepository,
        mockTeamsAndRepositoryConnector,
        mockVulnerabilitiesConnector,
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
        mockDerivedDeployedDependencyRepository,
        mockDerivedLatestDependencyRepository,
        mockDerivedModuleRepository,
        controller
      )
    }
  }
}
