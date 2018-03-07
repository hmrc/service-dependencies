/*
 * Copyright 2018 HM Revenue & Customs
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

import com.kenshoo.play.metrics.DisabledMetrics
import org.eclipse.egit.github.core.RequestError
import org.eclipse.egit.github.core.client.RequestException
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FreeSpec, Matchers, OptionValues}
import uk.gov.hmrc.githubclient.APIRateLimitExceededException
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.{Github, GithubSearchError}
import uk.gov.hmrc.servicedependencies.config._
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.connector.model.{GithubInstance, Repository}
import uk.gov.hmrc.servicedependencies.connector.{TeamsAndRepositoriesConnector, model}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.presistence.RepositoryLibraryDependenciesRepository
import uk.gov.hmrc.time.DateTimeUtils

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DependenciesDataSourceSpec extends FreeSpec with Matchers with ScalaFutures with MockitoSugar with IntegrationPatience with OptionValues {

  implicit val hc = HeaderCarrier()

  val timeNow = DateTimeUtils.now
  val timeInThePast = DateTimeUtils.now.minusDays(1)


  class GithubStub(
                    val lookupMap: Map[String, Option[String]],
                    val repositoryAndVersions: Map[String, Version] = Map.empty
                  ) extends Github {

    override val gh = null

    override def resolveTag(version: String) = version

    override val tagPrefix: String = "?-?"

    override def findLatestVersion(repoName: String): Option[Version] = {
      repositoryAndVersions.get(repoName)
    }

  }

  "getDependenciesForAllRepositories" - {

    val curatedDependencyConfig = CuratedDependencyConfig(Nil, Seq("library1", "library2", "library3"), Seq(OtherDependencyConfig("sbt", Some(Version(1, 2, 3)))))

    "should persist the dependencies (library, plugin and other) for each repository" in new TestCase {

      override def repositories: Seq[model.Repository] = Seq(repo1, repo2, repo3)

      val captor: ArgumentCaptor[MongoRepositoryDependencies] = ArgumentCaptor.forClass(classOf[MongoRepositoryDependencies])

      when(repositoryLibraryDependenciesRepository.update(any())).thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil).futureValue

      Mockito.verify(repositoryLibraryDependenciesRepository, Mockito.times(3)).update(captor.capture())

      val allStoredDependencies: List[MongoRepositoryDependencies] = captor.getAllValues.toList

      allStoredDependencies.size should be(3)

      allStoredDependencies(0)

      allStoredDependencies(0).libraryDependencies should contain theSameElementsAs Seq(LibraryDependency("library1", Version(1, 0, 1)))
      allStoredDependencies(1).libraryDependencies should contain theSameElementsAs Seq(LibraryDependency("library1", Version(1, 0, 2)), LibraryDependency("library2", Version(2, 0, 3)))
      allStoredDependencies(2).libraryDependencies should contain theSameElementsAs Seq(LibraryDependency("library1", Version(1, 0, 3)), LibraryDependency("library3", Version(3, 0, 4)))

      allStoredDependencies(0).sbtPluginDependencies should contain theSameElementsAs Seq(SbtPluginDependency("plugin1", Version(100, 0, 1)))
      allStoredDependencies(1).sbtPluginDependencies should contain theSameElementsAs Seq(SbtPluginDependency("plugin1", Version(100, 0, 2)), SbtPluginDependency("plugin2", Version(200, 0, 3)))
      allStoredDependencies(2).sbtPluginDependencies should contain theSameElementsAs Seq(SbtPluginDependency("plugin1", Version(100, 0, 3)), SbtPluginDependency("plugin3", Version(300, 0, 4)))

      allStoredDependencies(0).otherDependencies should contain theSameElementsAs Seq(OtherDependency("sbt", Version(1, 13, 100)))
      allStoredDependencies(1).otherDependencies should contain theSameElementsAs Seq(OtherDependency("sbt", Version(1, 13, 200)))
      allStoredDependencies(2).otherDependencies should contain theSameElementsAs Seq(OtherDependency("sbt", Version(1, 13, 300)))
    }

    "should persist the empty dependencies (non-sbt - i.e: no library, no plugins and no other dependencies)" in new TestCase {

      override def repositories: Seq[model.Repository] = Seq(repo5)

      val captor: ArgumentCaptor[MongoRepositoryDependencies] = ArgumentCaptor.forClass(classOf[MongoRepositoryDependencies])

      when(repositoryLibraryDependenciesRepository.update(any())).thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil).futureValue

      Mockito.verify(repositoryLibraryDependenciesRepository, Mockito.times(1)).update(captor.capture())

      val allStoredDependencies: List[MongoRepositoryDependencies] = captor.getAllValues.toList

      allStoredDependencies.size should be(1)
      allStoredDependencies(0).libraryDependencies shouldBe Nil
      allStoredDependencies(0).sbtPluginDependencies shouldBe Nil
      allStoredDependencies(0).otherDependencies shouldBe Nil
    }


    "should NOT persist the dependency when there not been any updates to the git repository" in new TestCase {


      override def repositories: Seq[model.Repository] = Seq(repo1.copy(lastActive = timeInThePast))

      Mockito.verifyZeroInteractions(repositoryLibraryDependenciesRepository)

      val currentDependencyEntries = Seq(MongoRepositoryDependencies("repo1", Nil, Nil, Nil, timeInThePast.plusMinutes(1)))
      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, currentDependencyEntries).futureValue

    }

    "should NOT terminate with exception when the repository is not found in teams-and-repositories" in new TestCase {


      override def repositories: Seq[model.Repository] = Seq(repo1, repo2)

      when(teamsAndRepositoriesConnector.getRepository(any())(any())).thenAnswer(new Answer[Future[Option[Repository]]] {
        override def answer(invocation: InvocationOnMock): Future[Option[Repository]] = {
          if( invocation.getArgument[String](0) == repo2.name) {
            Future(Some(repo2))
          } else {
            Future(None)
          }
        }
      })

      val captor: ArgumentCaptor[MongoRepositoryDependencies] = ArgumentCaptor.forClass(classOf[MongoRepositoryDependencies])
      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil).futureValue
      Mockito.verify(repositoryLibraryDependenciesRepository, Mockito.times(1)).update(captor.capture())

      val allStoredDependencies: List[MongoRepositoryDependencies] = captor.getAllValues.toList
      allStoredDependencies.size should be(1)
      allStoredDependencies(0).repositoryName shouldBe repo2.name
    }


    "should return the github open results over enterprise when a repository exists in both" in new TestCase {

       val repoOnBothGithub = repo1.copy(githubUrls = Seq(GithubInstance("github-com", "GitHub.com"), GithubInstance("github-enterprise", "Github Enterprise")))
       val repoOnGithubEnterprise = repo1.copy(githubUrls = Seq(GithubInstance("github-enterprise", "Github Enterprise")))
       val repoOnGithubCOm = repo1.copy(githubUrls = Seq(GithubInstance("github-com", "GitHub.com")))

      override def repositories: Seq[model.Repository] = Seq(repo1, repo2, repo3 )

      dependenciesDataSource.githubForRepository(repoOnBothGithub) shouldBe dependenciesDataSource.githubOpen
      dependenciesDataSource.githubForRepository(repoOnGithubEnterprise) shouldBe dependenciesDataSource.githubEnterprise
      dependenciesDataSource.githubForRepository(repoOnGithubCOm) shouldBe dependenciesDataSource.githubOpen
    }

    "should short circuit operation when RequestException is thrown for api rate limiting reason" in new TestCase {


      override def repositories: Seq[model.Repository] = Seq(repo1, repo2, repo3, repo4)

      var callCount = 0
      override def github: Github = new GithubStub(Map()) {
        override def findVersionsForMultipleArtifacts(repoName: String, curatedDependencyConfig: CuratedDependencyConfig): Either[GithubSearchError, GithubSearchResults] = {
          if (repoName == repo3.name) {
            val requestError = mock[RequestError]
            when(requestError.getMessage).thenReturn("rate limit exceeded")
            throw new APIRateLimitExceededException(new RequestException(requestError, 403))
          }

          callCount += 1

          Right(lookupTable(repoName))
        }
      }

      when(repositoryLibraryDependenciesRepository.update(any())).thenReturn(Future.successful(mock[MongoRepositoryDependencies]))
      intercept[Exception] {
        dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil).futureValue
      }

      callCount shouldBe 2
    }

    "should get the new repos (non existing in db) first" in new TestCase {

      override def repositories: Seq[model.Repository] = Seq(repo1, repo2, repo3, repo4)


      val dependenciesAlreadyInDb = Seq(
        MongoRepositoryDependencies("repo1", Nil, Nil, Nil, timeNow),
        MongoRepositoryDependencies("repo3", Nil, Nil, Nil, timeNow)
      )

      val captor: ArgumentCaptor[MongoRepositoryDependencies] = ArgumentCaptor.forClass(classOf[MongoRepositoryDependencies])

      when(repositoryLibraryDependenciesRepository.update(any())).thenReturn(Future.successful(mock[MongoRepositoryDependencies]))

      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, dependenciesAlreadyInDb).futureValue

      Mockito.verify(repositoryLibraryDependenciesRepository, Mockito.times(2)).update(captor.capture())

      val allStoredDependencies: List[MongoRepositoryDependencies] = captor.getAllValues.toList

      allStoredDependencies.size should be(2)
      allStoredDependencies(0).repositoryName shouldBe "repo2"
      allStoredDependencies(1).repositoryName shouldBe "repo4"
    }


  }


  "getLatestLibrariesVersions" - {

    def extractLibVersion(results: Seq[LibraryVersion], lib: String): Option[Version] =
      results.filter(_.libraryName == lib).head.version

    "should get the latest library version" in new TestCase {
      override def repositories: Seq[model.Repository] = Seq(repo1, repo2, repo3)

      override def github = new GithubStub(Map(),
        Map("library1" -> Version(1, 0, 0), "library2" -> Version(2, 0, 0), "library3" -> Version(3, 0, 0)))

      val curatedListOfLibraries = Seq("library1", "library2", "library3")

      val results = dependenciesDataSource.getLatestLibrariesVersions(curatedListOfLibraries)

      // 3 is for "library1", "library2" and "library3"
      results.size shouldBe 3

      extractLibVersion(results, "library1") shouldBe Some(Version(1, 0, 0))
      extractLibVersion(results, "library2") shouldBe Some(Version(2, 0, 0))
      extractLibVersion(results, "library3") shouldBe Some(Version(3, 0, 0))

    }

  }

  "getLatestSbtPluginVersions" - {

    "should get the latest sbt plugin version" in new TestCase {

      override def repositories: Seq[model.Repository] = Seq(repo1, repo2, repo3)
      override def github = new GithubStub(
        lookupMap = Map(),
        repositoryAndVersions = Map(
          "sbtplugin1" -> Version(1, 0, 0),
          "sbtplugin2" -> Version(2, 0, 0),
          "sbtplugin3" -> Version(3, 0, 0))
      )

      val curatedListOfSbtPluginConfigs = Seq(
        SbtPluginConfig("org", "sbtplugin1", Some(Version(1, 2, 3))),
        SbtPluginConfig("org", "sbtplugin2", Some(Version(1, 2, 3))),
        SbtPluginConfig("org", "sbtplugin3", Some(Version(1, 2, 3)))
      )

      val results = dependenciesDataSource.getLatestSbtPluginVersions(curatedListOfSbtPluginConfigs)

      results should contain {
        SbtPluginVersion("sbtplugin1", Some(Version(1, 0, 0)))
        SbtPluginVersion("sbtplugin2", Some(Version(2, 0, 0)))
        SbtPluginVersion("sbtplugin3", Some(Version(3, 0, 0)))
      }
    }
  }

  trait TestCase {

    def repositories: Seq[model.Repository]


    val repo1 = model.Repository("repo1", timeInThePast, Seq("PlatOps"), Seq(GithubInstance("github-com", "Github")))
    val repo2 = model.Repository("repo2", timeInThePast, Seq("PlatOps"), Seq(GithubInstance("github-com", "Github")))
    val repo3 = model.Repository("repo3", timeInThePast, Seq("PlatOps"), Seq(GithubInstance("github-com", "Github")))
    val repo4 = model.Repository("repo4", timeInThePast, Seq("PlatOps"), Seq(GithubInstance("github-com", "Github")))
    val repo5 = model.Repository("repo5", timeInThePast, Seq("PlatOps"), Seq(GithubInstance("github-com", "Github")))


    def lookupTable(repo: String) = repo match {
      case "repo1" => GithubSearchResults(
        Map("plugin1" -> Some(Version(100, 0, 1))),
        Map("library1" -> Some(Version(1, 0, 1))),
        Map("sbt" -> Some(Version(1, 13, 100)))
      )
      case "repo2" => GithubSearchResults(
        Map("plugin1" -> Some(Version(100, 0, 2)), "plugin2" -> Some(Version(200, 0, 3))),
        Map("library1" -> Some(Version(1, 0, 2)), "library2" -> Some(Version(2, 0, 3))),
        Map("sbt" -> Some(Version(1, 13, 200)))
      )
      case "repo3" => GithubSearchResults(
        Map("plugin1" -> Some(Version(100, 0, 3)), "plugin3" -> Some(Version(300, 0, 4))),
        Map("library1" -> Some(Version(1, 0, 3)), "library3" -> Some(Version(3, 0, 4))),
        Map("sbt" -> Some(Version(1, 13, 300)))
      )
      case "repo4" => GithubSearchResults(
        Map.empty,
        Map("library1" -> Some(Version(1, 0, 3)), "library3" -> Some(Version(3, 0, 4))),
        Map("sbt" -> Some(Version(1, 13, 400)))
      )
      case "repo5" => GithubSearchResults(
        Map.empty,
        Map.empty,
        Map("sbt" -> None)
      )
      case _ => throw new RuntimeException(s"No entry in lookup function for repoName: $repo")
    }

    val mockedGitApiConfig = mock[GitApiConfig]
    when(mockedGitApiConfig.apiUrl).thenReturn("http://some.api.url")
    when(mockedGitApiConfig.key).thenReturn("key-12345")

    val mockedDependenciesConfig = mock[ServiceDependenciesConfig]
    when(mockedDependenciesConfig.githubApiEnterpriseConfig).thenReturn(mockedGitApiConfig)
    when(mockedDependenciesConfig.githubApiOpenConfig).thenReturn(mockedGitApiConfig)

    val repositoryLibraryDependenciesRepository = mock[RepositoryLibraryDependenciesRepository]

    val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    when(teamsAndRepositoriesConnector.getTeamsForServices()(any())).thenReturn(Future.successful(Map("service1" -> Seq("PlatOps", "WebOps"))))
    when(teamsAndRepositoriesConnector.getAllRepositories()(any())).thenReturn(Future.successful(repositories.map(_.name)))
    when(teamsAndRepositoriesConnector.getRepository(any())(any())).thenAnswer(new Answer[Future[Option[Repository]]] {
      override def answer(invocation: InvocationOnMock): Future[Option[Repository]] = {
        Future(repositories.find(_.name == invocation.getArgument[String](0)))
      }
    })

    def github: Github = new GithubStub(Map()) {

      override def findLatestVersion(repoName: String): Option[Version] = super.findLatestVersion(repoName)

      override def findVersionsForMultipleArtifacts(repoName: String, curatedDependencyConfig: CuratedDependencyConfig): Either[GithubSearchError, GithubSearchResults] =
        Right(lookupTable(repoName))
    }

    val dependenciesDataSource = new DependenciesDataSource(teamsAndRepositoriesConnector, mockedDependenciesConfig, repositoryLibraryDependenciesRepository, new DisabledMetrics()) {

      override lazy val githubEnterprise = github
      override lazy val githubOpen = github

      override def now: DateTime = timeNow
    }
  }

}
