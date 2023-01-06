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

import java.time.{Instant, LocalDate}
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.connector.{ArtifactoryConnector, ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence._
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedGroupArtefactRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DependencyDataUpdatingServiceSpec
  extends AnyWordSpec
     with MockitoSugar
     with Matchers
     with OptionValues
     with MongoSupport
     with IntegrationPatience {

  private val timeForTest = Instant.now()

  "reloadLatestVersions" should {
    "call the dependency version update function on the repository" in {
      val boot = new Boot(CuratedDependencyConfig(
        sbtPlugins = Nil
      , libraries  = List(DependencyConfig(name = "libYY", group= "uk.gov.hmrc", latestVersion = None))
      , others     = Nil
      ))

      when(boot.mockArtifactoryConnector.findLatestVersion(group = "uk.gov.hmrc", artefact = "libYY"))
        .thenReturn(Future.successful(Map(ScalaVersion.SV_None -> Version("1.1.1"))))

      when(boot.mockLatestVersionRepository.update(any()))
        .thenReturn(Future.successful(mock[LatestVersion]))

      boot.dependencyUpdatingService.reloadLatestVersions().futureValue

      verify(boot.mockLatestVersionRepository, times(1))
        .update(LatestVersion(name = "libYY", group = "uk.gov.hmrc", version = Version("1.1.1"), updateDate = timeForTest))
    }
  }

  "versionsToUpdate" should {
    "merge static configuration with derived group artefacts" in {
      val boot = new Boot(CuratedDependencyConfig(
        sbtPlugins = Nil
      , libraries  = List(
                       DependencyConfig(name = "libYY", group= "uk.gov.hmrc", latestVersion = None),
                       DependencyConfig(name = "lib2" , group= "uk.gov.hmrc", latestVersion = Some(Version("1.0.0")))
                     )
      , others     = Nil
      ))

      when(boot.derivedGroupArtefactRepository.findGroupsArtefacts)
        .thenReturn(Future.successful(List(
          GroupArtefacts("uk.gov.hmrc"    , List("lib1", "lib2")),
          GroupArtefacts("uk.gov.hmrc.sub", List("lib3")),
        )))

      boot.dependencyUpdatingService.versionsToUpdate().futureValue should contain theSameElementsAs List(
        DependencyConfig("lib1" , "uk.gov.hmrc"    , None),
        DependencyConfig("lib2" , "uk.gov.hmrc"    , Some(Version("1.0.0"))),
        DependencyConfig("lib3" , "uk.gov.hmrc.sub", None),
        DependencyConfig("libYY", "uk.gov.hmrc"    , None)
      )
    }
    "include any non HMRC dependencies with bobby rules" in {
      val boot = new Boot(CuratedDependencyConfig(sbtPlugins = Nil, libraries  = Nil, others     = Nil))

      when(boot.mockServiceConfigsConnector.getBobbyRules)
        .thenReturn(
          Future.successful(BobbyRules(Map(
          ("com.other", "lib4") -> List(BobbyRule("com.other", "lib4",
            BobbyVersionRange.parse("(1.1.0,1.3.0]").get,
            "naughty lib",
            LocalDate.of(2020,1,1)))
        ))))

      boot.dependencyUpdatingService.versionsToUpdate().futureValue shouldBe List(
        DependencyConfig("lib4", "com.other", None)
      )
    }
    "override non HMRC dependencies version with curated list" in {
      val boot = new Boot(CuratedDependencyConfig(
        sbtPlugins = Nil,
        libraries = List(
          DependencyConfig(name = "lib4" , group= "com.other", latestVersion = Some(Version("1.5.0"))),
        ),
        others = Nil
      ))

      when(boot.mockServiceConfigsConnector.getBobbyRules)
        .thenReturn(
          Future.successful(BobbyRules(Map(
            ("com.other", "lib4") -> List(BobbyRule("com.other", "lib4",
              BobbyVersionRange.parse("(1.1.0,1.3.0]").get,
              "naughty lib",
              LocalDate.of(2020,1,1)))
          ))))

      boot.dependencyUpdatingService.versionsToUpdate().futureValue shouldBe List(
        DependencyConfig("lib4", "com.other", Some(Version("1.5.0")))
      )
    }
  }

  class Boot(dependencyConfig: CuratedDependencyConfig) {
    val mockServiceDependenciesConfig        = mock[ServiceDependenciesConfig]
    val mockLatestVersionRepository          = mock[LatestVersionRepository]
    val derivedGroupArtefactRepository       = mock[DerivedGroupArtefactRepository]
    val mockTeamsAndRepositoriesConnector    = mock[TeamsAndRepositoriesConnector]
    val mockArtifactoryConnector             = mock[ArtifactoryConnector]
    val mockServiceConfigsConnector          = mock[ServiceConfigsConnector]

    when(mockServiceDependenciesConfig.curatedDependencyConfig)
      .thenReturn(dependencyConfig)

    when(derivedGroupArtefactRepository.findGroupsArtefacts)
      .thenReturn(Future.successful(Seq.empty))

    when(mockServiceConfigsConnector.getBobbyRules)
      .thenReturn(Future.successful(BobbyRules(Map.empty)))

    val dependencyUpdatingService = new DependencyDataUpdatingService(
        mockServiceDependenciesConfig
      , mockLatestVersionRepository
      , derivedGroupArtefactRepository
      , mockTeamsAndRepositoriesConnector
      , mockArtifactoryConnector
      , mockServiceConfigsConnector
      ) {
        override def now: Instant = timeForTest
      }
  }
}
