/*
 * Copyright 2019 HM Revenue & Customs
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
import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{Matchers, WordSpec}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model.{MongoRepositoryDependency, _}
import uk.gov.hmrc.servicedependencies.persistence.RepositoryLibraryDependenciesRepository
import uk.gov.hmrc.servicedependencies.util.DateUtil

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RepositoryDependenciesSourceSpec
    extends WordSpec
    with Matchers
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience {

  "getTeamsLibraries" should {
    "return all the libraries for each team filtering out repos with names ending with -history" in new Setup {
      val repository1 = "repo1"
      val repository2 = "repo2"
      val repository3 = "repo3-history"

      when(teamsAndRepositoriesConnector.getTeamsWithRepositories()(any()))
        .thenReturn(
          Future.successful(
            Seq(Team(
              "team A",
              Some(Map("Service" -> Seq(repository1, repository2, repository3), "Library" -> Seq("library A")))))))

      val libraryDependencies1 = Seq(
        MongoRepositoryDependency("lib1", Version(1, 1, 0)),
        MongoRepositoryDependency("lib2", Version(1, 2, 0))
      )
      val libraryDependencies2 = Seq(
        MongoRepositoryDependency("lib1", Version(2, 1, 0)),
        MongoRepositoryDependency("lib2", Version(2, 2, 0))
      )

      val sbtPluginDependencies1 = Seq(
        MongoRepositoryDependency("plugin1", Version(10, 1, 0)),
        MongoRepositoryDependency("plugin2", Version(10, 2, 0))
      )

      val sbtPluginDependencies2 = Seq(
        MongoRepositoryDependency("plugin1", Version(20, 1, 0)),
        MongoRepositoryDependency("plugin2", Version(20, 2, 0))
      )

      when(repositoryLibraryDependenciesRepository.getAllEntries)
        .thenReturn(
          Future.successful(Seq(
            MongoRepositoryDependencies(repository1, libraryDependencies1, sbtPluginDependencies1, Nil, timeForTest),
            MongoRepositoryDependencies(repository2, libraryDependencies2, sbtPluginDependencies2, Nil, timeForTest),
            MongoRepositoryDependencies(repository3, libraryDependencies2, Nil, Nil, timeForTest)
          )))

      libraryDependenciesSource.getTeamsLibraries.futureValue should contain theSameElementsAs Seq(
        TeamRepos(
          "team_a",
          Map(
            repository1 -> Seq(
              MongoRepositoryDependency("lib1", Version(1, 1, 0)),
              MongoRepositoryDependency("lib2", Version(1, 2, 0)),
              MongoRepositoryDependency("plugin1", Version(10, 1, 0)),
              MongoRepositoryDependency("plugin2", Version(10, 2, 0))
            ),
            repository2 -> Seq(
              MongoRepositoryDependency("lib1", Version(2, 1, 0)),
              MongoRepositoryDependency("lib2", Version(2, 2, 0)),
              MongoRepositoryDependency("plugin1", Version(20, 1, 0)),
              MongoRepositoryDependency("plugin2", Version(20, 2, 0))
            )
          )
        )
      )
    }
  }

  "metrics" should {
    "count each library dependency of a service and the total number of services per team" in new Setup {
      val repository1 = "repo1"
      val repository2 = "repo2"
      val repository3 = "repo3"

      private val teamA = "team A"
      private val teamB = "team B"

      when(teamsAndRepositoriesConnector.getTeamsWithRepositories()(any()))
        .thenReturn(
          Future.successful(
            Seq(
              Team(teamA, Some(Map("Service" -> Seq(repository1, repository2), "Library" -> Seq("library A")))),
              Team(teamB, Some(Map("Service" -> Seq(repository3))))
            )))

      val libraryDependencies1 = Seq(
        MongoRepositoryDependency("lib1", Version(1, 1, 0)),
        MongoRepositoryDependency("lib2", Version(1, 2, 0))
      )
      val libraryDependencies2 = Seq(
        MongoRepositoryDependency("lib1", Version(2, 1, 0)),
        MongoRepositoryDependency("lib2", Version(2, 2, 0))
      )

      val sbtPluginDependencies1 = Seq(
        MongoRepositoryDependency("plugin1", Version(10, 1, 0)),
        MongoRepositoryDependency("plugin2", Version(10, 2, 0))
      )

      val sbtPluginDependencies2 = Seq(
        MongoRepositoryDependency("plugin1", Version(20, 1, 0)),
        MongoRepositoryDependency("plugin2", Version(20, 2, 0))
      )

      when(repositoryLibraryDependenciesRepository.getAllEntries)
        .thenReturn(
          Future.successful(Seq(
            MongoRepositoryDependencies(repository1, libraryDependencies1, sbtPluginDependencies1, Nil, timeForTest),
            MongoRepositoryDependencies(repository2, libraryDependencies2, sbtPluginDependencies2, Nil, timeForTest),
            MongoRepositoryDependencies(repository3, libraryDependencies2, Nil, Nil, timeForTest)
          )))

      libraryDependenciesSource.metrics.futureValue shouldBe Map(
        s"teams.team_a.services.repo1.libraries.lib1.versions.1_1_0"     -> 1,
        s"teams.team_a.services.repo1.libraries.lib2.versions.1_2_0"     -> 1,
        s"teams.team_a.services.repo1.libraries.plugin1.versions.10_1_0" -> 1,
        s"teams.team_a.services.repo1.libraries.plugin2.versions.10_2_0" -> 1,
        s"teams.team_a.services.repo2.libraries.lib1.versions.2_1_0"     -> 1,
        s"teams.team_a.services.repo2.libraries.lib2.versions.2_2_0"     -> 1,
        s"teams.team_a.services.repo2.libraries.plugin1.versions.20_1_0" -> 1,
        s"teams.team_a.services.repo2.libraries.plugin2.versions.20_2_0" -> 1,
        s"teams.team_b.services.repo3.libraries.lib1.versions.2_1_0"     -> 1,
        s"teams.team_b.services.repo3.libraries.lib2.versions.2_2_0"     -> 1,
        s"teams.team_a.servicesCount"                                    -> 2,
        s"teams.team_b.servicesCount"                                    -> 1
      )

    }
  }

  trait Setup {
    implicit val hc                             = HeaderCarrier()
    val teamsAndRepositoriesConnector           = mock[TeamsAndRepositoriesConnector]
    val repositoryLibraryDependenciesRepository = mock[RepositoryLibraryDependenciesRepository]
    lazy val libraryDependenciesSource =
      new RepositoryDependenciesSource(teamsAndRepositoriesConnector, repositoryLibraryDependenciesRepository)

    val timeForTest = DateUtil.now
  }

}
