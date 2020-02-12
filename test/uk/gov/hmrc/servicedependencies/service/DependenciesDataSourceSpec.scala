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

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.eclipse.egit.github.core.RequestError
import org.eclipse.egit.github.core.client.RequestException
import org.mockito.ArgumentMatchers.any
import org.mockito.invocation.InvocationOnMock
import org.mockito.{ArgumentCaptor, Mockito, MockitoSugar}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.OptionValues
import uk.gov.hmrc.githubclient.{APIRateLimitExceededException, GitApiConfig}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config._
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, LibraryConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.connector.model.{Repository, RepositoryInfo}
import uk.gov.hmrc.servicedependencies.connector.{ArtifactoryConnector, GithubConnector, TeamsAndRepositoriesConnector, TeamsForServices}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.RepositoryLibraryDependenciesRepository
import uk.gov.hmrc.servicedependencies.{Github, GithubSearchError}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class DependenciesDataSourceSpec
    extends AnyFreeSpec
    with Matchers
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience
    with OptionValues {

  implicit val hc = HeaderCarrier()

  val timeNow       = Instant.now()
  val timeInThePast = Instant.now().minus(1, ChronoUnit.DAYS)

  "getDependenciesForAllRepositories" - {

    val curatedDependencyConfig = CuratedDependencyConfig(
        Nil
      , List(
          LibraryConfig(name = "library1", group = "uk.gov.hmrc", latestVersion = None)
        , LibraryConfig(name = "library2", group = "uk.gov.hmrc", latestVersion = None)
        , LibraryConfig(name = "library3", group = "uk.gov.hmrc", latestVersion = None)
        )
      , List(OtherDependencyConfig(name = "sbt", group = "org.scala-sbt", latestVersion = Some(Version(1, 2, 3))))
      )

    "should persist the dependencies (library, plugin and other) for each repository" in new TestCase {

      override def repositories: Seq[Repository] = Seq(repo1, repo2, repo3)

      val captor: ArgumentCaptor[MongoRepositoryDependencies] =
        ArgumentCaptor.forClass(classOf[MongoRepositoryDependencies])

      when(mockedRepositoryLibraryDependenciesRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil).futureValue

      Mockito.verify(mockedRepositoryLibraryDependenciesRepository, Mockito.times(3)).update(captor.capture())

      val allStoredDependencies: List[MongoRepositoryDependencies] = captor.getAllValues.toList

      allStoredDependencies.size should be(3)

      allStoredDependencies(0)

      allStoredDependencies(0).libraryDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "library1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 1)))
      allStoredDependencies(1).libraryDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "library1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 2)),
        MongoRepositoryDependency(name = "library2", group = "uk.gov.hmrc", currentVersion = Version(2, 0, 3)))
      allStoredDependencies(2).libraryDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "library1", group = "uk.gov.hmrc", currentVersion = Version(1, 0, 3)),
        MongoRepositoryDependency(name = "library3", group = "uk.gov.hmrc", currentVersion = Version(3, 0, 4)))

      allStoredDependencies(0).sbtPluginDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(100, 0, 1)))
      allStoredDependencies(1).sbtPluginDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(100, 0, 2)),
        MongoRepositoryDependency(name = "plugin2", group = "uk.gov.hmrc", currentVersion = Version(200, 0, 3)))
      allStoredDependencies(2).sbtPluginDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "plugin1", group = "uk.gov.hmrc", currentVersion = Version(100, 0, 3)),
        MongoRepositoryDependency(name = "plugin3", group = "uk.gov.hmrc", currentVersion = Version(300, 0, 4)))

      allStoredDependencies(0).otherDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(1, 13, 100)))
      allStoredDependencies(1).otherDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(1, 13, 200)))
      allStoredDependencies(2).otherDependencies should contain theSameElementsAs Seq(
        MongoRepositoryDependency(name = "sbt", group = "org.scala-sbt", currentVersion = Version(1, 13, 300)))
    }

    "should persist the empty dependencies (non-sbt - i.e: no library, no plugins and no other dependencies)" in new TestCase {

      override def repositories: Seq[Repository] = Seq(repo5)

      val captor: ArgumentCaptor[MongoRepositoryDependencies] =
        ArgumentCaptor.forClass(classOf[MongoRepositoryDependencies])

      when(mockedRepositoryLibraryDependenciesRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil).futureValue

      Mockito.verify(mockedRepositoryLibraryDependenciesRepository, Mockito.times(1)).update(captor.capture())

      val allStoredDependencies: List[MongoRepositoryDependencies] = captor.getAllValues.toList

      allStoredDependencies.size                     should be(1)
      allStoredDependencies(0).libraryDependencies   shouldBe Nil
      allStoredDependencies(0).sbtPluginDependencies shouldBe Nil
      allStoredDependencies(0).otherDependencies     shouldBe Nil
    }

    "should NOT persist the dependency when there not been any updates to the git repository" in new TestCase {

      override def repositories: Seq[Repository] = Seq(repo1.copy(lastActive = timeInThePast))

      Mockito.verifyNoInteractions(mockedRepositoryLibraryDependenciesRepository)

      val currentDependencyEntries =
        Seq(MongoRepositoryDependencies("repo1", Nil, Nil, Nil, timeInThePast.plus(1, ChronoUnit.MINUTES)))
      dependenciesDataSource
        .persistDependenciesForAllRepositories(curatedDependencyConfig, currentDependencyEntries)
        .futureValue
    }

    "should persist the dependency if the forced flag is true, even if there are no updates to the git repository" in new TestCase {
      override def repositories: Seq[Repository] = Seq(repo1.copy(lastActive = timeInThePast), repo2, repo3)

      val captor: ArgumentCaptor[MongoRepositoryDependencies] =
        ArgumentCaptor.forClass(classOf[MongoRepositoryDependencies])

      when(mockedRepositoryLibraryDependenciesRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      val currentDependencyEntries =
        Seq(MongoRepositoryDependencies("repo1", Nil, Nil, Nil, timeInThePast.plus(1, ChronoUnit.MINUTES)))

      dependenciesDataSource
        .persistDependenciesForAllRepositories(curatedDependencyConfig, currentDependencyEntries, force = true)
        .futureValue

      Mockito.verify(mockedRepositoryLibraryDependenciesRepository, Mockito.times(3)).update(captor.capture())

      val allStoredDependencies: List[MongoRepositoryDependencies] = captor.getAllValues.toList
      allStoredDependencies.size should be(3)
    }

    "should NOT call getRepository every time it updates a repository" in new TestCase {

      override def repositories: Seq[Repository] = Seq(repo1, repo2)

      when(mockedRepositoryLibraryDependenciesRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil).futureValue

      Mockito.verify(mockedTeamsAndRepositoriesConnector, Mockito.never()).getRepository(_)
    }

    "should short circuit operation when RequestException is thrown for api rate limiting reason" in new TestCase {

      override def repositories: Seq[Repository] = Seq(repo1, repo2, repo3, repo4)

      var callCount = 0
      override def githubStub: Github = new Github(null, null) {
        override def findVersionsForMultipleArtifacts(
          repoName: String,
          curatedDependencyConfig: CuratedDependencyConfig): Either[GithubSearchError, GithubSearchResults] = {
          if (repoName == repo3.name) {
            val requestError = mock[RequestError]
            when(requestError.getMessage).thenReturn("rate limit exceeded")
            throw new APIRateLimitExceededException(new RequestException(requestError, 403))
          }

          callCount += 1

          Right(lookupTable(repoName))
        }
      }

      when(mockedRepositoryLibraryDependenciesRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      intercept[Exception] {
        dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil).futureValue
      }

      callCount shouldBe 2
    }

    "should get the new repos (non existing in db) first" in new TestCase {

      override def repositories: Seq[Repository] = Seq(repo1, repo2, repo3, repo4)

      val dependenciesAlreadyInDb = Seq(
        MongoRepositoryDependencies("repo1", Nil, Nil, Nil, timeNow),
        MongoRepositoryDependencies("repo3", Nil, Nil, Nil, timeNow)
      )

      val captor: ArgumentCaptor[MongoRepositoryDependencies] =
        ArgumentCaptor.forClass(classOf[MongoRepositoryDependencies])

      when(mockedRepositoryLibraryDependenciesRepository.update(any()))
        .thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      dependenciesDataSource
        .persistDependenciesForAllRepositories(curatedDependencyConfig, dependenciesAlreadyInDb)
        .futureValue

      Mockito.verify(mockedRepositoryLibraryDependenciesRepository, Mockito.times(2)).update(captor.capture())

      val allStoredDependencies: List[MongoRepositoryDependencies] = captor.getAllValues.toList

      allStoredDependencies.size              should be(2)
      allStoredDependencies(0).repositoryName shouldBe "repo2"
      allStoredDependencies(1).repositoryName shouldBe "repo4"
    }
  }

  trait TestCase {

    def repositories: Seq[Repository]

    val repo1 = Repository("repo1", timeInThePast, Seq("PlatOps"))
    val repo2 = Repository("repo2", timeInThePast, Seq("PlatOps"))
    val repo3 = Repository("repo3", timeInThePast, Seq("PlatOps"))
    val repo4 = Repository("repo4", timeInThePast, Seq("PlatOps"))
    val repo5 = Repository("repo5", timeInThePast, Seq("PlatOps"))

    def lookupTable(repo: String) = repo match {
      case "repo1" =>
        GithubSearchResults(
          Map(("plugin1" , "uk.gov.hmrc"  ) -> Some(Version(100, 0, 1))),
          Map(("library1", "uk.gov.hmrc"  ) -> Some(Version(1, 0, 1))),
          Map(("sbt"     , "org.scala-sbt") -> Some(Version(1, 13, 100)))
        )
      case "repo2" =>
        GithubSearchResults(
          Map( ("plugin1" , "uk.gov.hmrc"  ) -> Some(Version(100, 0, 2))
             , ("plugin2" , "uk.gov.hmrc"  ) -> Some(Version(200, 0, 3))
             )
        , Map( ("library1", "uk.gov.hmrc"  ) -> Some(Version(1, 0, 2))
             , ("library2", "uk.gov.hmrc"  ) -> Some(Version(2, 0, 3))
             )
        , Map( ("sbt"     , "org.scala-sbt") -> Some(Version(1, 13, 200)))
        )
      case "repo3" =>
        GithubSearchResults(
          Map( ("plugin1" , "uk.gov.hmrc"  ) -> Some(Version(100, 0, 3))
             , ("plugin3" , "uk.gov.hmrc"  ) -> Some(Version(300, 0, 4))
             )
        , Map( ("library1", "uk.gov.hmrc"  ) -> Some(Version(1, 0, 3))
             , ("library3", "uk.gov.hmrc"  ) -> Some(Version(3, 0, 4)))
        , Map( ("sbt"     , "org.scala-sbt") -> Some(Version(1, 13, 300)))
        )
      case "repo4" =>
        GithubSearchResults(
          Map.empty
        , Map( ("library1", "uk.gov.hmrc"  ) -> Some(Version(1, 0, 3))
             , ("library3", "uk.gov.hmrc"  ) -> Some(Version(3, 0, 4))
             )
        , Map( ("sbt"     , "org.scala-sbt") -> Some(Version(1, 13, 400)))
        )
      case "repo5" =>
        GithubSearchResults(
          Map.empty,
          Map.empty,
          Map(("sbt", "org.scala-sbt") -> None)
        )
      case _ => throw new RuntimeException(s"No entry in lookup function for repoName: $repo")
    }

    val mockedGitApiConfig = mock[GitApiConfig]
    when(mockedGitApiConfig.apiUrl).thenReturn("http://some.api.url")
    when(mockedGitApiConfig.key).thenReturn("key-12345")

    val mockedDependenciesConfig = mock[ServiceDependenciesConfig]
    when(mockedDependenciesConfig.githubApiOpenConfig).thenReturn(mockedGitApiConfig)

    val mockedRepositoryLibraryDependenciesRepository = mock[RepositoryLibraryDependenciesRepository]

    val mockedTeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]

    when(mockedTeamsAndRepositoriesConnector.getTeamsForServices()(any()))
      .thenReturn(Future.successful(TeamsForServices(Map("service1" -> Seq("PlatOps", "WebOps")))))

    when(mockedTeamsAndRepositoriesConnector.getAllRepositories()(any()))
      .thenReturn(Future.successful(repositories.map(r => RepositoryInfo(r.name, r.lastActive, r.lastActive, "Service"))))

    when(mockedTeamsAndRepositoriesConnector.getRepository(any())(any())).thenAnswer( (i: InvocationOnMock) => Future(repositories.find(_.name == i.getArgument[String](0))))

    def githubStub(): Github = new Github(null, null) {
      override def findVersionsForMultipleArtifacts(
        repoName: String,
        curatedDependencyConfig: CuratedDependencyConfig): Either[GithubSearchError, GithubSearchResults] =
        Right(lookupTable(repoName))
    }

    val githubConnector = new GithubConnector(githubStub())

    val dependenciesDataSource = new DependenciesDataSource(
        teamsAndRepositoriesConnector = mockedTeamsAndRepositoriesConnector
      , config                        = mockedDependenciesConfig
      , githubConnector               = githubConnector
      , repoLibDepRepository          = mockedRepositoryLibraryDependenciesRepository
      ) {
      override def now: Instant       = timeNow
    }
  }
}
