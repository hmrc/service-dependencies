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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
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

  "DerivedViewsService.updateDerivedViewsForAllRepos" should {
    "process test repositories that are not in latestMeta" in {
      val testRepo = TeamsAndRepositoriesConnector.Repository(
        name           = "test-repo"
      , teamNames      = Seq("PlatOps")
      , digitalService = None
      , repoType       = RepoType.Test
      , isArchived     = false
      )

      val latestMeta = Seq(("service1", Version("1.0.0"))) // test-repo is NOT in latestMeta
      val testMetaArtefact = MetaArtefact(
        name     = "test-repo"
      , version  = Version("2.0.0")
      , uri      = "https://artefacts/metadata/test-repo-v2.0.0.meta.tgz"
      , gitUrl   = Some("https://github.com/hmrc/test-repo")
      , modules  = Seq.empty
      , created  = java.time.Instant.now()
      , latest   = false // No latest flag
      )

      when(mockedTeamsAndRepositoriesConnector.getAllRepositories(Some(false)))
        .thenReturn(Future.successful(Seq(testRepo)))

      when(mockedTeamsAndRepositoriesConnector.getDecommissionedRepositories())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedMetaArtefactRepository.findLatest())
        .thenReturn(Future.successful(latestMeta))

      when(mockedDeploymentRepository.findDeployed())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedMetaArtefactRepository.find("test-repo"))
        .thenReturn(Future.successful(None)) // No latest: true flag

      when(mockedMetaArtefactRepository.findAllVersions("test-repo"))
        .thenReturn(Future.successful(Seq(testMetaArtefact)))

      // Mock for find - use ArgumentMatchers.any() for all parameters
      // Note: Mockito requires all parameters to use matchers when any() is used
      when(mockedDerivedLatestDependencyRepository.find(
        group = any[Option[String]],
        artefact = any[Option[String]],
        repoType = any[Option[Seq[RepoType]]],
        scopes = any[Option[Seq[DependencyScope]]],
        repoName = any[Option[String]],
        repoVersion = any[Option[Version]]
      ))
        .thenReturn(Future.successful(Seq.empty)) // Not in collection yet

      when(mockedDerivedLatestDependencyRepository.update(any[String], any[List[uk.gov.hmrc.servicedependencies.model.MetaArtefactDependency]]))
        .thenReturn(Future.unit)

      when(mockedDerivedLatestDependencyRepository.deleteMany(any[Seq[TeamsAndRepositoriesConnector.DecommissionedRepository]]))
        .thenReturn(Future.unit)

      when(mockedDerivedDeployedDependencyRepository.find(any[String], any[Version]))
        .thenReturn(Future.successful(Seq.empty))

      when(mockedDerivedDeployedDependencyRepository.delete(any[String], any[Option[Version]], any[Seq[Version]]))
        .thenReturn(Future.unit)

      when(mockedDerivedDeployedDependencyRepository.deleteMany(any[Seq[TeamsAndRepositoriesConnector.DecommissionedRepository]]))
        .thenReturn(Future.unit)

      when(mockedServiceConfigsConnector.getBobbyRules())
        .thenReturn(Future.successful(BobbyRules(Map.empty[(String, String), List[BobbyRule]])))

      when(mockedDerivedBobbyReportRepository.update(any[String], any[Seq[BobbyReport]]))
        .thenReturn(Future.unit)

      when(mockedDerivedBobbyReportRepository.deleteMany(any[Seq[TeamsAndRepositoriesConnector.DecommissionedRepository]]))
        .thenReturn(Future.unit)

      when(mockedDerivedModuleRepository.populateAll())
        .thenReturn(Future.unit)

      when(mockedDerivedGroupArtefactRepository.populateAll())
        .thenReturn(Future.unit)

      underTest.updateDerivedViewsForAllRepos().futureValue

      // Verify that test repository was processed
      verify(mockedMetaArtefactRepository).find("test-repo")
      verify(mockedMetaArtefactRepository).findAllVersions("test-repo")
      verify(mockedDerivedLatestDependencyRepository).update(eqTo("test-repo"), any[List[uk.gov.hmrc.servicedependencies.model.MetaArtefactDependency]])
    }
  }
}
