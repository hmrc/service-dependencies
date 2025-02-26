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

package uk.gov.hmrc.servicedependencies.service

import org.mockito.Mockito.{times, verify, verifyNoMoreInteractions, when}
import org.mockito.ArgumentMatchers.any
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, JdkVersionRepository, SbtVersionRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedGroupArtefactRepository

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlugInfoServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  given HeaderCarrier = HeaderCarrier()

  val group    = "group"
  val artefact = "artefact"
  val scope    = DependencyScope.Compile

  val v100 = MetaArtefactDependency(
    repoName       = "service1"
  , repoVersion    = Version("v1")
  , repoType       = RepoType.Service
  , teams          = List.empty
  , digitalService = None
  , depGroup       = group
  , depArtefact    = artefact
  , depVersion     = Version("1.0.0")
  , compileFlag    = false
  , providedFlag   = true
  , testFlag       = true
  , itFlag         = true
  , buildFlag      = true
  )

  val v200 = v100.copy(depVersion = Version("2.0.0"))
  val v205 = v100.copy(depVersion = Version("2.0.5"))

  "SlugInfoService.getSlugInfo" should {
    "support retrieval of a SlugInfo by flag" in new GetSlugInfoFixture {
      when(boot.mockedSlugInfoRepository.getSlugInfo(SlugName, SlugInfoFlag.QA))
        .thenReturn(Future.successful(Some(sampleSlugInfo)))

      boot.service.getSlugInfo(SlugName, SlugInfoFlag.QA).futureValue shouldBe Some(sampleSlugInfo)
    }

    "return None when no SlugInfos are found matching the target name and flag" in new GetSlugInfoFixture {
      when(boot.mockedSlugInfoRepository.getSlugInfo(SlugName, SlugInfoFlag.QA))
        .thenReturn(Future.successful(None))

      boot.service.getSlugInfo(SlugName, SlugInfoFlag.QA).futureValue shouldBe None
    }

    "support retrieval of a SlugInfo by version" in new GetSlugInfoFixture {
      val targetVersion = Version("1.2.3")
      when(boot.mockedSlugInfoRepository.getSlugInfo(SlugName, targetVersion))
        .thenReturn(Future.successful(Some(sampleSlugInfo)))

      boot.service.getSlugInfo(SlugName, targetVersion).futureValue shouldBe Some(sampleSlugInfo)
    }

    "return None when no SlugInfos are found matching the target name and version" in new GetSlugInfoFixture {
      val targetVersion = Version("1.2.3")
      when(boot.mockedSlugInfoRepository.getSlugInfo(SlugName, targetVersion))
        .thenReturn(Future.successful(None))

      boot.service.getSlugInfo(SlugName, targetVersion).futureValue shouldBe None
    }
  }

  "SlugInfoService.addSlugInfo" should {
    "mark the slug as latest if it is the first slug with that name" in new GetSlugInfoFixture {
      when(boot.mockedSlugVersionRepository.getMaxVersion(sampleSlugInfo.name))
        .thenReturn(Future.successful(None))
      when(boot.mockedSlugInfoRepository.add(any))
        .thenReturn(Future.unit)
      when(boot.mockedDeploymentRepository.markLatest(any, any))
        .thenReturn(Future.unit)

      boot.service.addSlugInfo(sampleSlugInfo).futureValue

      verify(boot.mockedDeploymentRepository, times(1)).markLatest(sampleSlugInfo.name, sampleSlugInfo.version)
    }

    "mark the slug as latest if the version is latest" in new GetSlugInfoFixture {
      val slugv1 = sampleSlugInfo.copy(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = slugv1.copy(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugVersionRepository.getMaxVersion(sampleSlugInfo.name))
        .thenReturn(Future.successful(Some(slugv2.version)))
      when(boot.mockedSlugInfoRepository.add(any))
        .thenReturn(Future.unit)
      when(boot.mockedDeploymentRepository.markLatest(any, any))
        .thenReturn(Future.unit)

      boot.service.addSlugInfo(slugv2).futureValue
      verify(boot.mockedDeploymentRepository, times(1)).markLatest(slugv2.name, Version("2.0.0"))
    }

    "not use the latest flag from sqs/artefact processor" in new GetSlugInfoFixture {
      val slugv1 = sampleSlugInfo.copy(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = slugv1.copy(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugVersionRepository.getMaxVersion(sampleSlugInfo.name))
        .thenReturn(Future.successful(Some(slugv2.version)))
      when(boot.mockedSlugInfoRepository.add(any))
        .thenReturn(Future.unit)

      boot.service.addSlugInfo(slugv1).futureValue

      verify(boot.mockedSlugInfoRepository, times(1)).add(slugv1)
      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }

    "not mark the slug as latest if there is a later one already in the collection" in new GetSlugInfoFixture {
      val slugv1 = sampleSlugInfo.copy(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = slugv1.copy(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugVersionRepository.getMaxVersion(sampleSlugInfo.name))
        .thenReturn(Future.successful(Some(slugv2.version)))
      when(boot.mockedSlugInfoRepository.add(any))
        .thenReturn(Future.unit)

      boot.service.addSlugInfo(slugv1).futureValue

      verifyNoMoreInteractions(boot.mockedDeploymentRepository)
    }
  }

  case class Boot(
    mockedSlugInfoRepository                 : SlugInfoRepository
  , mockedSlugVersionRepository              : SlugVersionRepository
  , mockedJdkVersionRepository               : JdkVersionRepository
  , mockedSbtVersionRepository               : SbtVersionRepository
  , mockedDeploymentRepository               : DeploymentRepository
  , mockedTeamsAndRepositoriesConnector      : TeamsAndRepositoriesConnector
  , service                                  : SlugInfoService
  , mockedDerivedGroupArtefactRepository     : DerivedGroupArtefactRepository
  )

  object Boot {
    def init: Boot = {
      val mockedSlugInfoRepository             = mock[SlugInfoRepository]
      val mockedSlugVersionRepository          = mock[SlugVersionRepository]
      val mockedJdkVersionRepository           = mock[JdkVersionRepository]
      val mockedSbtVersionRepository           = mock[SbtVersionRepository]
      val mockedDeploymentRepository           = mock[DeploymentRepository]
      val mockedTeamsAndRepositoriesConnector  = mock[TeamsAndRepositoriesConnector]
      val mockedDerivedGroupArtefactRepository = mock[DerivedGroupArtefactRepository]

      val service = SlugInfoService(
            mockedSlugInfoRepository
          , mockedSlugVersionRepository
          , mockedJdkVersionRepository
          , mockedSbtVersionRepository
          , mockedDeploymentRepository
          , mockedTeamsAndRepositoriesConnector
          , mockedDerivedGroupArtefactRepository
          )
      Boot(
          mockedSlugInfoRepository
        , mockedSlugVersionRepository
        , mockedJdkVersionRepository
        , mockedSbtVersionRepository
        , mockedDeploymentRepository
        , mockedTeamsAndRepositoriesConnector
        , service
        , mockedDerivedGroupArtefactRepository
        )
    }
  }

  private trait GetSlugInfoFixture {
    val SlugName = "a-slug-name"
    val sampleSlugInfo = SlugInfo(
      uri                   = "sample-uri",
      created               = Instant.parse("2019-12-12T13:14:00.000Z"),
      name                  = SlugName,
      version               = Version(major = 1, minor = 2, patch = 3),
      teams                 = Nil,
      runnerVersion         = "sample-runner-version",
      classpath             = "sample-classpath",
      java                  = JavaInfo(version = "sample-java-version", vendor = "sample-java-vendor", kind = "sample-java-kind"),
      sbtVersion            = Some("1.4.9"),
      repoUrl               = Some("https://github.com/hmrc/test.git"),
      applicationConfig     = "sample-applcation-config",
      slugConfig            = "sample-slug-config"
    )

    val boot = Boot.init
  }
}
