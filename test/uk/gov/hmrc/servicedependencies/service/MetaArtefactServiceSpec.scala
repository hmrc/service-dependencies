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

import java.time.Instant
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{GitHubProxyConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, SlugInfoRepository, SlugVersionRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MetaArtefactServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val mockedSlugInfoRepository            = mock[SlugInfoRepository]
  private val mockedSlugVersionRepository         = mock[SlugVersionRepository]
  private val mockedDeploymentRepository          = mock[DeploymentRepository]
  private val mockedTeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
  private val mockedReleasesApiConnector          = mock[ReleasesApiConnector]
  private val mockedGitHubProxyConnector          = mock[GitHubProxyConnector]
  private val underTest = new MetaArtefactService(
    mockedSlugInfoRepository
  , mockedSlugVersionRepository
  , mockedDeploymentRepository
  , mockedTeamsAndRepositoriesConnector
  , mockedReleasesApiConnector
  , mockedGitHubProxyConnector
  )

  "MetaArtefactService.updateMetadata" should {
    "clear latest flag for decommisioned services" in {
      val decomissionedServices = List("service1")

      when(mockedSlugInfoRepository.getUniqueSlugNames)
        .thenReturn(Future.successful(Seq.empty))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere)
        .thenReturn(Future.successful(List.empty))

      when(mockedGitHubProxyConnector.decomissionedServices)
        .thenReturn(Future.successful(decomissionedServices))

      when(mockedDeploymentRepository.getNames(SlugInfoFlag.Latest))
        .thenReturn(Future.successful(Seq.empty))

      when(mockedTeamsAndRepositoriesConnector.getAllRepositories(eqTo(Some(false)))(any[HeaderCarrier]))
        .thenReturn(Future.successful(Seq.empty))

      when(mockedDeploymentRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      underTest.updateMetadata().futureValue

      verify(mockedDeploymentRepository).clearFlags(SlugInfoFlag.values, decomissionedServices)
    }

    "clear latest flag for deleted/archived services" in {
      def toRepositoryInfo(name: String) =
        RepositoryInfo(
            name          = name
          , createdAt     = Instant.parse("2015-09-15T16:27:38.000Z")
          , lastUpdatedAt = Instant.parse("2017-05-19T11:00:51.000Z")
          , repoType      = "Service"
          , language      = None
          )

      val knownSlugs          = List("service1", "service2", "service3")
      val activeServices      = List("service1", "service3").map(toRepositoryInfo)
      val servicesToBeCleared = List("service2")

      when(mockedSlugInfoRepository.getUniqueSlugNames)
        .thenReturn(Future.successful(knownSlugs))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere)
        .thenReturn(Future.successful(List.empty))

      when(mockedGitHubProxyConnector.decomissionedServices)
        .thenReturn(Future.successful(List.empty))

      when(mockedDeploymentRepository.getNames(SlugInfoFlag.Latest))
        .thenReturn(Future.successful(knownSlugs))

      when(mockedTeamsAndRepositoriesConnector.getAllRepositories(eqTo(Some(false)))(any[HeaderCarrier]))
        .thenReturn(Future.successful(activeServices))

      when(mockedDeploymentRepository.clearFlag(any[SlugInfoFlag], any[String]))
        .thenReturn(Future.unit)

      when(mockedDeploymentRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      underTest.updateMetadata().futureValue

      verify(mockedDeploymentRepository).clearFlags(List(SlugInfoFlag.Latest), servicesToBeCleared)
    }
  }
}
