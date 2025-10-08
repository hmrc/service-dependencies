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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ReleasesApiConnector, ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, MetaArtefactRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DerivedViewsServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  given HeaderCarrier = HeaderCarrier()

  private val mockedTeamsAndRepositoriesConnector         = mock[TeamsAndRepositoriesConnector      ]
  private val mockedServiceConfigsConnector               = mock[ServiceConfigsConnector            ]
  private val mockedReleasesApiConnector                  = mock[ReleasesApiConnector               ]
  private val mockedMetaArtefactRepository                = mock[MetaArtefactRepository             ]
  private val mockedSlugInfoRepository                    = mock[SlugInfoRepository                 ]
  private val mockedSlugVersionRepository                 = mock[SlugVersionRepository              ]
  private val mockedDeploymentRepository                  = mock[DeploymentRepository               ]
  private val mockedDerivedGroupArtefactRepository        = mock[DerivedGroupArtefactRepository     ]
  private val mockedDerivedModuleRepository               = mock[DerivedModuleRepository            ]
  private val mockedDerivedDeployedDependencyRepository   = mock[DerivedDeployedDependencyRepository]
  private val mockedDerivedLatestDependencyRepository     = mock[DerivedLatestDependencyRepository  ]
  private val mockedDerivedBobbyReportRepository          = mock[DerivedBobbyReportRepository       ]

  private val underTest = DerivedViewsService(
    teamsAndRepositoriesConnector       = mockedTeamsAndRepositoriesConnector
  , serviceConfigsConnector             = mockedServiceConfigsConnector
  , releasesApiConnector                = mockedReleasesApiConnector
  , metaArtefactRepository              = mockedMetaArtefactRepository
  , slugInfoRepository                  = mockedSlugInfoRepository
  , slugVersionRepository               = mockedSlugVersionRepository
  , deploymentRepository                = mockedDeploymentRepository
  , derivedGroupArtefactRepository      = mockedDerivedGroupArtefactRepository
  , derivedModuleRepository             = mockedDerivedModuleRepository
  , derivedDeployedDependencyRepository = mockedDerivedDeployedDependencyRepository
  , derivedLatestDependencyRepository   = mockedDerivedLatestDependencyRepository
  , derivedBobbyReportRepository        = mockedDerivedBobbyReportRepository
  )

  "DerivedViewsService.updateDeploymentData" should {
    "clear latest flag for decommissioned services" in {
      val decommissionedServices = List(TeamsAndRepositoriesConnector.DecommissionedRepository("service1"))

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndRepositoriesConnector.getDecommissionedRepositories(Some(RepoType.Service)))
        .thenReturn(Future.successful(decommissionedServices))

      when(mockedDeploymentRepository.getNames(SlugInfoFlag.Latest))
        .thenReturn(Future.successful(Seq.empty))

      when(mockedTeamsAndRepositoriesConnector.getAllRepositories(Some(false)))
        .thenReturn(Future.successful(Seq.empty))

      when(mockedDeploymentRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      underTest.updateDeploymentDataForAllServices().futureValue

      verify(mockedDeploymentRepository).clearFlags(SlugInfoFlag.values.toList, List("service1"))
    }

    "clear latest flag for deleted/archived services" in {
      def toRepositoryInfo(name: String) =
        TeamsAndRepositoriesConnector.Repository(
          name           = name
        , teamNames      = Seq("PlatOps", "Webops")
        , digitalService = None
        , repoType       = RepoType.Service
        , isArchived     = false
        )

      val knownSlugs          = List("service1", "service2", "service3")
      val activeServices      = List("service1", "service3").map(toRepositoryInfo)
      val servicesToBeCleared = List("service2")

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownSlugs))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndRepositoriesConnector.getDecommissionedRepositories(Some(RepoType.Service)))
        .thenReturn(Future.successful(List.empty))

      when(mockedDeploymentRepository.getNames(SlugInfoFlag.Latest))
        .thenReturn(Future.successful(knownSlugs))

      when(mockedTeamsAndRepositoriesConnector.getAllRepositories(Some(false)))
        .thenReturn(Future.successful(activeServices))

      when(mockedDeploymentRepository.clearFlag(any[SlugInfoFlag], any[String]))
        .thenReturn(Future.unit)

      when(mockedDeploymentRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      when(mockedSlugVersionRepository.getMaxVersion(any[String]))
        .thenReturn(Future.successful(None))

      underTest.updateDeploymentDataForAllServices().futureValue

      verify(mockedDeploymentRepository).clearFlags(List(SlugInfoFlag.Latest), servicesToBeCleared)
    }
  }
}
