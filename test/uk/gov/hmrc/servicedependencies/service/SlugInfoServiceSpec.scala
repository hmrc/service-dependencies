/*
 * Copyright 2020 HM Revenue & Customs
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

import java.time.LocalDateTime
import java.time.Month.DECEMBER

import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{GithubConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector, TeamsForServices}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedGroupArtefactRepository, DerivedServiceDependenciesRepository}
import uk.gov.hmrc.servicedependencies.persistence.{JdkVersionRepository, SlugInfoRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlugInfoServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

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

      when(boot.mockedServiceDependenciesRepository.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact))
        .thenReturn(Future(Seq(v100, v200, v205)))

      when(boot.mockedTeamsAndRepositoriesConnector.getTeamsForServices)
        .thenReturn(Future(TeamsForServices(Map.empty)))

      whenReady(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("[1.0.1,)"))){ f =>
        f shouldBe Seq(v200, v205)
      }
      whenReady(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("(,1.0.1]"))){ f =>
        f shouldBe Seq(v100)
      }
      whenReady(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("[2.0.0]"))){ f =>
        f shouldBe Seq(v200)
      }
    }

    "include non-parseable versions" in {
      val boot = Boot.init

      val bad = v100.copy(depVersion  = "r938")

      when(boot.mockedServiceDependenciesRepository.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact))
        .thenReturn(Future(Seq(v100, v200, v205, bad)))

      when(boot.mockedTeamsAndRepositoriesConnector.getTeamsForServices)
        .thenReturn(Future(TeamsForServices(Map.empty)))

      whenReady(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("[1.0.1,)"))){ f =>
        f shouldBe Seq(v200, v205, bad)
      }
      whenReady(boot.service.findServicesWithDependency(SlugInfoFlag.Latest, group, artefact, BobbyVersionRange("(,1.0.1]"))){ f =>
        f shouldBe Seq(v100, bad)
      }
    }
  }

  "SlugInfoService.getSlugInfo" should {
    "support retrieval of a SlugInfo by flag" in new GetSlugInfoFixture {
      when(boot.mockedSlugInfoRepository.getSlugInfo(SlugName, SlugInfoFlag.Latest))
        .thenReturn(Future.successful(Some(sampleSlugInfo)))

      boot.service.getSlugInfo(SlugName, SlugInfoFlag.Latest).futureValue shouldBe Some(sampleSlugInfo)
    }

    "return None when no SlugInfos are found matching the target name and flag" in new GetSlugInfoFixture {
      when(boot.mockedSlugInfoRepository.getSlugInfo(SlugName, SlugInfoFlag.Latest))
        .thenReturn(Future.successful(None))

      boot.service.getSlugInfo(SlugName, SlugInfoFlag.Latest).futureValue shouldBe None
    }

    "support retrieval of a SlugInfo by version" in new GetSlugInfoFixture {
      val targetVersion = "1.2.3"
      when(boot.mockedSlugInfoRepository.getSlugInfos(SlugName, Some(targetVersion)))
        .thenReturn(Future.successful(Seq(sampleSlugInfo)))

      boot.service.getSlugInfo(SlugName, targetVersion).futureValue shouldBe Some(sampleSlugInfo)
    }

    "return None when no SlugInfos are found matching the target name and version" in new GetSlugInfoFixture {
      val targetVersion = "1.2.3"
      when(boot.mockedSlugInfoRepository.getSlugInfos(SlugName, Some(targetVersion)))
        .thenReturn(Future.successful(Nil))

      boot.service.getSlugInfo(SlugName, targetVersion).futureValue shouldBe None
    }
  }

  "SlugInfoService.addSlugInfo" should {
    "mark the slug as latest if it is the first slug with that name" in new GetSlugInfoFixture {
      when(boot.mockedSlugInfoRepository.getSlugInfos(sampleSlugInfo.name, None))
        .thenReturn(Future.successful(List.empty))
      when(boot.mockedSlugInfoRepository.add(any))
        .thenReturn(Future.successful(true))
      when(boot.mockedSlugInfoRepository.markLatest(any, any))
        .thenReturn(Future.successful(()))

      boot.service.addSlugInfo(sampleSlugInfo).futureValue

      verify(boot.mockedSlugInfoRepository, times(1)).markLatest(sampleSlugInfo.name, sampleSlugInfo.version)
      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }

    "mark the slug as latest if the version is latest" in new GetSlugInfoFixture {
      val slugv1 = sampleSlugInfo.copy(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = slugv1.copy(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugInfoRepository.getSlugInfos(sampleSlugInfo.name, None))
        .thenReturn(Future.successful(List(slugv1, slugv2)))
      when(boot.mockedSlugInfoRepository.add(any))
        .thenReturn(Future.successful(true))
      when(boot.mockedSlugInfoRepository.markLatest(any, any))
        .thenReturn(Future.successful(()))

      boot.service.addSlugInfo(slugv2).futureValue
      verify(boot.mockedSlugInfoRepository, times(1)).markLatest(slugv2.name, Version("2.0.0"))

      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }

    "not use the latest flag from sqs/artefact processor" in new GetSlugInfoFixture {
      val slugv1 = sampleSlugInfo.copy(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = slugv1.copy(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugInfoRepository.getSlugInfos(sampleSlugInfo.name, None))
        .thenReturn(Future.successful(List(slugv2)))
      when(boot.mockedSlugInfoRepository.add(any))
        .thenReturn(Future.successful(true))

      boot.service.addSlugInfo(slugv1).futureValue

      verify(boot.mockedSlugInfoRepository, times(1)).add(slugv1.copy(latest = false))
      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }

    "not mark the slug as latest if there is a later one already in the collection" in new GetSlugInfoFixture {
      val slugv1 = sampleSlugInfo.copy(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = slugv1.copy(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugInfoRepository.getSlugInfos(sampleSlugInfo.name, None))
        .thenReturn(Future.successful(List(slugv2)))
      when(boot.mockedSlugInfoRepository.add(any))
        .thenReturn(Future.successful(true))

      boot.service.addSlugInfo(slugv1).futureValue

      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }
  }

  "SlugInfoService.updateMetadata" should {
    "clear latest flag for decommisioned services" in {
      val boot = Boot.init

      val decomissionedServices = List("service1")

      when(boot.mockedSlugInfoRepository.getUniqueSlugNames)
        .thenReturn(Future.successful(Seq.empty))

      when(boot.mockedReleasesApiConnector.getWhatIsRunningWhere)
        .thenReturn(Future.successful(List.empty))

      when(boot.mockedGithubConnector.decomissionedServices)
        .thenReturn(Future.successful(decomissionedServices))

      when(boot.mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[String]))
        .thenReturn(Future.successful(()))

      boot.service.updateMetadata().futureValue

      decomissionedServices.foreach { serviceName =>
        SlugInfoFlag.values.foreach { flag =>
          verify(boot.mockedSlugInfoRepository).clearFlag(flag, serviceName)
        }
      }
    }
  }

  case class Boot(
    mockedSlugInfoRepository            : SlugInfoRepository
  , mockedServiceDependenciesRepository : DerivedServiceDependenciesRepository
  , mockedJdkVersionRespository         : JdkVersionRepository
  , mockedGroupArtefactRepository       : DerivedGroupArtefactRepository
  , mockedTeamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
  , mockedReleasesApiConnector          : ReleasesApiConnector
  , mockedGithubConnector               : GithubConnector
  , service                             : SlugInfoService
  )

  object Boot {
    def init: Boot = {
      val mockedSlugInfoRepository                = mock[SlugInfoRepository]
      val mockedDerivedSlugDependenciesRepository = mock[DerivedServiceDependenciesRepository]
      val mockedJdkVersionRepository              = mock[JdkVersionRepository]
      val mockedGroupArtefactRepository           = mock[DerivedGroupArtefactRepository]
      val mockedTeamsAndRepositoriesConnector     = mock[TeamsAndRepositoriesConnector]
      val mockedReleasesApiConnector              = mock[ReleasesApiConnector]
      val mockedGithubConnector                   = mock[GithubConnector]

      val service = new SlugInfoService(
            mockedSlugInfoRepository
          , mockedDerivedSlugDependenciesRepository
          , mockedJdkVersionRepository
          , mockedGroupArtefactRepository
          , mockedTeamsAndRepositoriesConnector
          , mockedReleasesApiConnector
          , mockedGithubConnector
          )
      Boot(
          mockedSlugInfoRepository
        , mockedDerivedSlugDependenciesRepository
        , mockedJdkVersionRepository
        , mockedGroupArtefactRepository
        , mockedTeamsAndRepositoriesConnector
        , mockedReleasesApiConnector
        , mockedGithubConnector
        , service
        )
    }
  }

  private trait GetSlugInfoFixture {
    val SlugName = "a-slug-name"
    val sampleSlugInfo = SlugInfo(
      uri               = "sample-uri",
      created           = LocalDateTime.of(2019, DECEMBER, 12, 13, 14),
      name              = SlugName,
      version           = Version(major = 1, minor = 2, patch = 3),
      teams             = Nil,
      runnerVersion     = "sample-runner-version",
      classpath         = "sample-classpath",
      java              = JavaInfo(version = "sample-java-version", vendor = "sample-java-vendor", kind = "sample-java-kind"),
      dependencies      = Nil,
      applicationConfig = "sample-applcation-config",
      slugConfig        = "sample-slug-config",
      latest            = true,
      production        = false,
      qa                = true,
      staging           = false,
      development       = true,
      externalTest      = false,
      integration       = false)

    val boot = Boot.init
  }
 }
