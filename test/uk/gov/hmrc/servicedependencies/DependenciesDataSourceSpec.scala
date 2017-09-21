/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies

import java.time.LocalDateTime
import java.util.{Base64, Date}

import org.eclipse.egit.github.core.client.RequestException
import org.eclipse.egit.github.core.service.RepositoryService
import org.eclipse.egit.github.core.{Repository, RepositoryContents, RequestError}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FreeSpec, Matchers, OptionValues}
import uk.gov.hmrc.githubclient.{ExtendedContentsService, GithubApiClient}
import uk.gov.hmrc.servicedependencies.TestHelpers.toDate
import uk.gov.hmrc.servicedependencies.config._
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.service._

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

class DependenciesDataSourceSpec extends FreeSpec with Matchers with ScalaFutures with MockitoSugar with IntegrationPatience with OptionValues {

  val stubbedTime = 123456l
  val testTimestampGenerator = new TimestampGenerator {
    override def now = stubbedTime
  }

  type FindVersionsForMultipleArtifactsF = (String, Seq[String]) => GithubSearchResults


  val teamNames = Seq("PlatOps", "WebOps")
  val serviceTeams = Map(
    "service1" -> teamNames,
    "service2" -> teamNames,
    "service3" -> teamNames)


  private val nowAsDate: Date = toDate(LocalDateTime.now())

  class GithubStub(
                    val lookupMap: Map[String, Option[String]],
                    val findVersionsForMultipleArtifactsF: Option[FindVersionsForMultipleArtifactsF] = None,
                    val repositoryAndVersions: Map[String, Version] = Map.empty
                  ) extends Github(Seq()) {

    override val gh = null

    override def resolveTag(version: String) = version

    override val tagPrefix: String = "?-?"

    override def findArtifactVersion(serviceName: String, artifact: String, versionOption: Option[String]): Option[Version] = {
      versionOption match {
        case Some(version) =>
          lookupMap.get(s"$serviceName-$version").map(version =>
            version.map(Version.parse)).getOrElse(None)
        case None => None
      }
    }


    override def findVersionsForMultipleArtifacts(repoName: String, curatedDependencyConfig: CuratedDependencyConfig, storedLastUpdateDateO: Option[Date]): Option[GithubSearchResults] =
      findVersionsForMultipleArtifactsF.map(_.apply(repoName, curatedDependencyConfig.libraries))


    override def findLatestVersion(repoName: String): Option[Version] = {
      repositoryAndVersions.get(repoName)
    }

  }

  val githubStub1 = new GithubStub(Map(
    "service1-1.1.3" -> Some("17.0.0"),
    "service1-1.2.1" -> Some("16.3.0"),
    "service1-1.1.2" -> Some("16.0.0")
  ))

  val githubStub2 = new GithubStub(Map(
    "service2-1.2.3" -> None,
    "service2-1.2.2" -> Some("15.0.0"),
    "service2-1.2.0" -> Some("15.0.0"),
    "service3-1.3.3" -> Some("16.3.0"),
    "missing-in-action-1.3.3" -> Some("17.0.0")
  ))


  "getDependenciesForAllRepositories" - {

    val unchangedRepoStoredLastGitUpdateDate = LocalDateTime.of(2017, 9, 1, 10, 0, 0)
    val changedRepoStoredLastGitUpdateDate = LocalDateTime.of(2017, 9, 1, 10, 0, 0)
    val changedRepoLastGitUpdateDate = changedRepoStoredLastGitUpdateDate.plusSeconds(1)


    def lookupTable(repo: String) = repo match {
      case "repo1" => GithubSearchResults(
        Map("plugin1" -> Some(Version(100, 0, 1))),
        Map("library1" -> Some(Version(1, 0, 1))),
        Map("sbt" -> Some(Version(1, 13, 100))),
        lastGitUpdateDate = None
      )
      case "repo2" => GithubSearchResults(
        Map("plugin1" -> Some(Version(100, 0, 2)), "plugin2" -> Some(Version(200, 0, 3))),
        Map("library1" -> Some(Version(1, 0, 2)), "library2" -> Some(Version(2, 0, 3))),
        Map("sbt" -> Some(Version(1, 13, 200))),
        lastGitUpdateDate = None
      )
      case "repo3" => GithubSearchResults(
        Map("plugin1" -> Some(Version(100, 0, 3)), "plugin3" -> Some(Version(300, 0, 4))),
        Map("library1" -> Some(Version(1, 0, 3)), "library3" -> Some(Version(3, 0, 4))),
        Map("sbt" -> Some(Version(1, 13, 300))),
        lastGitUpdateDate = None
      )
      case "repo4" => GithubSearchResults(
        Map.empty,
        Map("library1" -> Some(Version(1, 0, 3)), "library3" -> Some(Version(3, 0, 4))),
        Map("sbt" -> Some(Version(1, 13, 400))),
        lastGitUpdateDate = None
      )
      case "non-sbt-repo5" => GithubSearchResults(
        Map.empty,
        Map.empty,
        Map("sbt" -> None),
        lastGitUpdateDate = None
      )
      case "unchanged-repo6" =>
        GithubSearchResults(
          Map("plugin1" -> Some(Version(100, 0, 3)), "plugin3" -> Some(Version(300, 0, 4))),
          Map("library1" -> Some(Version(1, 0, 3)), "library3" -> Some(Version(3, 0, 4))),
          Map("sbt" -> Some(Version(1, 13, 300))),
          lastGitUpdateDate = Some(toDate(unchangedRepoStoredLastGitUpdateDate))
        )
      case "changed-repo7" =>
        GithubSearchResults(
          Map("plugin1" -> Some(Version(100, 0, 3)), "plugin3" -> Some(Version(300, 0, 4))),
          Map("library1" -> Some(Version(1, 0, 3)), "library3" -> Some(Version(3, 0, 4))),
          Map("sbt" -> Some(Version(1, 13, 300))),
          lastGitUpdateDate = Some(toDate(changedRepoLastGitUpdateDate))
        )
      case _ => throw new RuntimeException(s"No entry in lookup function for repoName: $repo")
    }

    def findVersionsForMultipleArtifactsF(repoName: String, artifacts: Seq[String]): GithubSearchResults = {
      println(s"repoName $repoName artifacts: $artifacts")
      lookupTable(repoName)
    }

    val githubStubForMultiArtifacts = new GithubStub(Map(), Some(findVersionsForMultipleArtifactsF))


    def getLibDependencies(results: Seq[MongoRepositoryDependencies], repo: String): Seq[LibraryDependency] =
      results.filter(_.repositoryName == repo).head.libraryDependencies

    def getDependencies(results: Seq[MongoRepositoryDependencies], repo: String): MongoRepositoryDependencies =
      results.filter(_.repositoryName == repo).head

    val curatedDependencyConfig = CuratedDependencyConfig(Nil, Seq("library1", "library2", "library3"), Seq(OtherDependencyConfig("sbt", Some(Version(1, 2, 3)))))

    val timestampF: () => Long = () => 1234l

    "should persist the dependencies (library, plugin and other) for each repository" in {

      val dependenciesDataSource = prepareUnderTestClass(Seq(githubStubForMultiArtifacts), Seq("repo1", "repo2", "repo3"))

      var callsToPersisterF = ListBuffer.empty[MongoRepositoryDependencies]
      val persisterF: MongoRepositoryDependencies => Future[MongoRepositoryDependencies] = { repositoryLibraryDependencies =>
        callsToPersisterF += repositoryLibraryDependencies
        Future.successful(repositoryLibraryDependencies)
      }


      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil, persisterF).futureValue

      callsToPersisterF.size shouldBe 3

      getDependencies(callsToPersisterF, "repo1").libraryDependencies should contain theSameElementsAs Seq(LibraryDependency("library1", Version(1, 0, 1)))
      getDependencies(callsToPersisterF, "repo2").libraryDependencies should contain theSameElementsAs Seq(LibraryDependency("library1", Version(1, 0, 2)), LibraryDependency("library2", Version(2, 0, 3)))
      getDependencies(callsToPersisterF, "repo3").libraryDependencies should contain theSameElementsAs Seq(LibraryDependency("library1", Version(1, 0, 3)), LibraryDependency("library3", Version(3, 0, 4)))

      getDependencies(callsToPersisterF, "repo1").sbtPluginDependencies should contain theSameElementsAs Seq(SbtPluginDependency("plugin1", Version(100, 0, 1)))
      getDependencies(callsToPersisterF, "repo2").sbtPluginDependencies should contain theSameElementsAs Seq(SbtPluginDependency("plugin1", Version(100, 0, 2)), SbtPluginDependency("plugin2", Version(200, 0, 3)))
      getDependencies(callsToPersisterF, "repo3").sbtPluginDependencies should contain theSameElementsAs Seq(SbtPluginDependency("plugin1", Version(100, 0, 3)), SbtPluginDependency("plugin3", Version(300, 0, 4)))

      getDependencies(callsToPersisterF, "repo1").otherDependencies should contain theSameElementsAs Seq(OtherDependency("sbt", Version(1, 13, 100)))
      getDependencies(callsToPersisterF, "repo2").otherDependencies should contain theSameElementsAs Seq(OtherDependency("sbt", Version(1, 13, 200)))
      getDependencies(callsToPersisterF, "repo3").otherDependencies should contain theSameElementsAs Seq(OtherDependency("sbt", Version(1, 13, 300)))


    }

    "should persist the empty dependencies (non-sbt - i.e: no library, no plugins and no other dependencies)" in {

      val dependenciesDataSource = prepareUnderTestClass(Seq(githubStubForMultiArtifacts), Seq("non-sbt-repo5"))

      var callsToPersisterF = ListBuffer.empty[MongoRepositoryDependencies]
      val persisterF: MongoRepositoryDependencies => Future[MongoRepositoryDependencies] = { repositoryLibraryDependencies =>
        callsToPersisterF += repositoryLibraryDependencies
        Future.successful(repositoryLibraryDependencies)
      }


      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil, persisterF).futureValue

      callsToPersisterF.size shouldBe 1

      getDependencies(callsToPersisterF, "non-sbt-repo5").libraryDependencies shouldBe Nil

      getDependencies(callsToPersisterF, "non-sbt-repo5").sbtPluginDependencies shouldBe Nil

      getDependencies(callsToPersisterF, "non-sbt-repo5").otherDependencies shouldBe Nil


    }

    "should persist the dependency with correct lastGitUpdateDate when there has been updates to the git repository" in {

      val dependenciesDataSource = prepareUnderTestClass(Seq(githubStubForMultiArtifacts), Seq("changed-repo7"))

      var callsToPersisterF = ListBuffer.empty[MongoRepositoryDependencies]
      val persisterF: MongoRepositoryDependencies => Future[MongoRepositoryDependencies] = { repositoryLibraryDependencies =>
        callsToPersisterF += repositoryLibraryDependencies
        Future.successful(repositoryLibraryDependencies)
      }

      val currentDependencyEntries = Seq(MongoRepositoryDependencies("changed-repo7", Nil, Nil, Nil, Some(toDate(changedRepoStoredLastGitUpdateDate))))
      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, currentDependencyEntries, persisterF).futureValue

      callsToPersisterF.size shouldBe 1
      getDependencies(callsToPersisterF, "changed-repo7").lastGitUpdateDate.value shouldBe toDate(changedRepoLastGitUpdateDate)
    }

    "should NOT persist the dependency when there not been any updates to the git repository" in {

      val dependenciesDataSource = prepareUnderTestClass(Seq(githubStubForMultiArtifacts), Seq("unchanged-repo6"))

      var callsToPersisterF = ListBuffer.empty[MongoRepositoryDependencies]
      val persisterF: MongoRepositoryDependencies => Future[MongoRepositoryDependencies] = { repositoryLibraryDependencies =>
        callsToPersisterF += repositoryLibraryDependencies
        Future.successful(repositoryLibraryDependencies)
      }

      val currentDependencyEntries = Seq(MongoRepositoryDependencies("unchanged-repo6", Nil, Nil, Nil, Some(toDate(unchangedRepoStoredLastGitUpdateDate))))
      dependenciesDataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, currentDependencyEntries, persisterF).futureValue

      callsToPersisterF.size shouldBe 0
    }

    def base64(s: String) = Base64.getEncoder.withoutPadding().encodeToString(s.getBytes())


    val mockedGithubEnterpriseApiClient = mock[GithubApiClient]
    val mockedGithubOpenApiClient = mock[GithubApiClient]

    val mockedExtendedContentsServiceForOpen = mock[ExtendedContentsService]
    val mockedExtendedContentsServiceForEnterprise = mock[ExtendedContentsService]

    when(mockedGithubOpenApiClient.contentsService).thenReturn(mockedExtendedContentsServiceForOpen)
    when(mockedGithubEnterpriseApiClient.contentsService).thenReturn(mockedExtendedContentsServiceForEnterprise)

    val mockedRepositoryService = mock[RepositoryService]
    when(mockedGithubEnterpriseApiClient.repositoryService).thenReturn(mockedRepositoryService)
    when(mockedGithubOpenApiClient.repositoryService).thenReturn(mockedRepositoryService)
    val mockedRepository = mock[Repository]
    when(mockedRepositoryService.getRepository(any(), any())).thenReturn(mockedRepository)
    when(mockedRepository.getPushedAt).thenReturn(nowAsDate)


    val dataSource = new DependenciesDataSource(teamsAndRepositoriesStub(Seq("repo1", "repo2", "repo3")), getMockedConfig(), testTimestampGenerator) {

      override lazy val gitEnterpriseClient = mockedGithubEnterpriseApiClient
      override lazy val gitOpenClient = mockedGithubOpenApiClient

    }


    "should examine appDependencies.scala before build.sbt" - {

      def checkTheIndex(buildFilePaths: Seq[String]) = {
        buildFilePaths.indexWhere(_.contains("AppDependencies.scala")) should be < buildFilePaths.indexWhere(_.contains("build.sbt"))
      }

      "for github open" in {
        val buildFilePaths = dataSource.GithubOpen.buildFilePaths
        checkTheIndex(buildFilePaths)
      }

      "for github exterprise" in {
        val buildFilePaths = dataSource.GithubEnterprise.buildFilePaths
        checkTheIndex(buildFilePaths)
      }
    }

    "should return the github open results over enterprise when a repository exists in both" in {

      val openContents =
        """
          | "org.something" %% "library1" % "1.0.0"
          | "org.something" %% "library2" % "2.0.0"
          | "org.something" %% "library3" % "3.0.0"
        """.stripMargin

      val enterpriseContents =
        """
          | "org.something" %% "library1" % "9.9.999"
          | "org.something" %% "library2" % "9.9.999"
          | "org.something" %% "library3" % "9.9.999"
        """.stripMargin

      when(mockedExtendedContentsServiceForOpen.getContents(any(), any())).thenReturn(List(new RepositoryContents().setContent(base64(openContents))).asJava)
      when(mockedExtendedContentsServiceForEnterprise.getContents(any(), any())).thenReturn(List(new RepositoryContents().setContent(base64(enterpriseContents))).asJava)

      var callsToPersisterF = ListBuffer.empty[MongoRepositoryDependencies]
      val persisterF: MongoRepositoryDependencies => Future[MongoRepositoryDependencies] = { repositoryLibraryDependencies =>
        callsToPersisterF += repositoryLibraryDependencies
        Future.successful(repositoryLibraryDependencies)
      }

      dataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil, persisterF).futureValue

      callsToPersisterF should contain theSameElementsAs Seq(
        MongoRepositoryDependencies("repo1", Seq(LibraryDependency("library1", Version(1, 0, 0)), LibraryDependency("library2", Version(2, 0, 0)), LibraryDependency("library3", Version(3, 0, 0))), Nil, Nil, Some(nowAsDate), stubbedTime),
        MongoRepositoryDependencies("repo2", Seq(LibraryDependency("library1", Version(1, 0, 0)), LibraryDependency("library2", Version(2, 0, 0)), LibraryDependency("library3", Version(3, 0, 0))), Nil, Nil, Some(nowAsDate), stubbedTime),
        MongoRepositoryDependencies("repo3", Seq(LibraryDependency("library1", Version(1, 0, 0)), LibraryDependency("library2", Version(2, 0, 0)), LibraryDependency("library3", Version(3, 0, 0))), Nil, Nil, Some(nowAsDate), stubbedTime)
      )
    }


    "should not return the dependencies that are deleted from open when a repository exists in both" in {

      val openContents =
        """
          | "org.something" %% "library1" % "1.0.0"
          | "org.something" %% "library3" % "3.0.0"
        """.stripMargin

      val enterpriseContents =
        """
          | "org.something" %% "library1" % "9.9.999"
          | "org.something" %% "library2" % "9.9.999"
          | "org.something" %% "library3" % "9.9.999"
        """.stripMargin


      when(mockedExtendedContentsServiceForOpen.getContents(any(), any())).thenReturn(List(new RepositoryContents().setContent(base64(openContents))).asJava)
      when(mockedExtendedContentsServiceForEnterprise.getContents(any(), any())).thenReturn(List(new RepositoryContents().setContent(base64(enterpriseContents))).asJava)

      var callsToPersisterF = ListBuffer.empty[MongoRepositoryDependencies]
      val persisterF: MongoRepositoryDependencies => Future[MongoRepositoryDependencies] = { repositoryLibraryDependencies =>
        callsToPersisterF += repositoryLibraryDependencies
        Future.successful(repositoryLibraryDependencies)
      }


      dataSource.persistDependenciesForAllRepositories(curatedDependencyConfig, Nil, persisterF).futureValue

      callsToPersisterF should contain theSameElementsAs List(
        MongoRepositoryDependencies("repo1", List(LibraryDependency("library1", Version(1, 0, 0)), LibraryDependency("library3", Version(3, 0, 0))), Nil, Nil, Some(nowAsDate), stubbedTime),
        MongoRepositoryDependencies("repo2", List(LibraryDependency("library1", Version(1, 0, 0)), LibraryDependency("library3", Version(3, 0, 0))), Nil, Nil, Some(nowAsDate), stubbedTime),
        MongoRepositoryDependencies("repo3", List(LibraryDependency("library1", Version(1, 0, 0)), LibraryDependency("library3", Version(3, 0, 0))), Nil, Nil, Some(nowAsDate), stubbedTime)
      )
    }

    "should short circuit operation when RequestException is thrown for api rate limiting reason" in {


      var callCount = 0

      def findVersionsF_withRateLimitException(repoName: String, artifacts: Seq[String]): GithubSearchResults = {
        if (callCount >= 2) {
          val requestError = mock[RequestError]
          when(requestError.getMessage).thenReturn("rate limit exceeded")
          throw new RequestException(requestError, 403)
        }

        callCount += 1

        println(s"repoName $repoName artifacts: $artifacts")
        lookupTable(repoName)
      }


      val githubStubWithRateLimitException = new GithubStub(Map(), Some(findVersionsF_withRateLimitException))

      val dataSource = prepareUnderTestClass(Seq(githubStubWithRateLimitException), Seq("repo1", "repo2", "repo3", "repo4"))

      dataSource.persistDependenciesForAllRepositories(
        curatedDependencyConfig = curatedDependencyConfig,
        //        timeStampGenerator = timestampF,
        currentDependencyEntries = Nil,
        persisterF = rlp => Future.successful(rlp)).futureValue


      callCount shouldBe 2
    }

    "should get the new repos (non existing in db) first" in {

      var callsToPersisterF = ListBuffer.empty[MongoRepositoryDependencies]

      val dataSource = prepareUnderTestClass(Seq(githubStubForMultiArtifacts), Seq("repo1", "repo2", "repo3", "repo4"))
      val persisterF: MongoRepositoryDependencies => Future[MongoRepositoryDependencies] = { repositoryLibraryDependencies =>
        callsToPersisterF += repositoryLibraryDependencies
        Future.successful(repositoryLibraryDependencies)
      }


      val dependenciesAlreadyInDb = Seq(
        MongoRepositoryDependencies("repo1", Nil, Nil, Nil, None),
        MongoRepositoryDependencies("repo3", Nil, Nil, Nil, None)
      )


      dataSource.persistDependenciesForAllRepositories(
        curatedDependencyConfig = curatedDependencyConfig,
        currentDependencyEntries = dependenciesAlreadyInDb,
        persisterF = persisterF).futureValue

      callsToPersisterF.size shouldBe 4
      callsToPersisterF.toList(0).repositoryName shouldBe "repo2"
      callsToPersisterF.toList(1).repositoryName shouldBe "repo4"
    }

    "should get the oldest updated repos next" in {
      var callsToPersisterF = ListBuffer.empty[MongoRepositoryDependencies]

      val dataSource = prepareUnderTestClass(Seq(githubStubForMultiArtifacts), Seq("repo1", "repo2", "repo3", "repo4"))

      val persisterF: MongoRepositoryDependencies => Future[MongoRepositoryDependencies] = { repositoryLibraryDependencies =>
        callsToPersisterF += repositoryLibraryDependencies
        Future.successful(repositoryLibraryDependencies)
      }


      val dependenciesAlreadyInDb = Seq(
        MongoRepositoryDependencies("repo1", Nil, Nil, Nil, None, 20000l),
        MongoRepositoryDependencies("repo3", Nil, Nil, Nil, None, 10000l) // <-- oldest record should get updated first
      )


      dataSource.persistDependenciesForAllRepositories(
        curatedDependencyConfig = curatedDependencyConfig,
        currentDependencyEntries = dependenciesAlreadyInDb,
        persisterF = persisterF).futureValue

      callsToPersisterF.size shouldBe 4
      callsToPersisterF.toList(2).repositoryName shouldBe "repo3"
      callsToPersisterF.toList(3).repositoryName shouldBe "repo1"
    }


  }


  "getLatestLibrariesVersions" - {
    val githubStubForLibraryVersions = new GithubStub(Map(), None,
      Map("library1" -> Version(1, 0, 0), "library2" -> Version(2, 0, 0), "library3" -> Version(3, 0, 0)))


    def extractLibVersion(results: Seq[LibraryVersion], lib: String): Option[Version] =
      results.filter(_.libraryName == lib).head.version

    "should get the latest library version" in {
      val curatedListOfLibraries = Seq("library1", "library2", "library3")

      val dataSource = prepareUnderTestClass(Seq(githubStubForLibraryVersions), Seq("repo1", "repo2", "repo3"))

      val results = dataSource.getLatestLibrariesVersions(curatedListOfLibraries)

      // 3 is for "library1", "library2" and "library3"
      results.size shouldBe 3

      extractLibVersion(results, "library1") shouldBe Some(Version(1, 0, 0))
      extractLibVersion(results, "library2") shouldBe Some(Version(2, 0, 0))
      extractLibVersion(results, "library3") shouldBe Some(Version(3, 0, 0))

    }

  }

  "getLatestSbtPluginVersions" - {
    val githubStubForSbtpluginVersions = new GithubStub(
      lookupMap = Map(),
      findVersionsForMultipleArtifactsF = None,
      repositoryAndVersions = Map(
        "sbtplugin1" -> Version(1, 0, 0),
        "sbtplugin2" -> Version(2, 0, 0),
        "sbtplugin3" -> Version(3, 0, 0))
    )


    def extractSbtPluginVersion(results: Seq[SbtPluginVersion], lib: String): Option[Version] =
      results.filter(_.sbtPluginName == lib).head.version

    "should get the latest sbt plugin version" in {
      val curatedListOfSbtPluginConfigs = Seq(
        SbtPluginConfig("org", "sbtplugin1", Some(Version(1, 2, 3))),
        SbtPluginConfig("org", "sbtplugin2", Some(Version(1, 2, 3))),
        SbtPluginConfig("org", "sbtplugin3", Some(Version(1, 2, 3)))
      )

      val dataSource = prepareUnderTestClass(Seq(githubStubForSbtpluginVersions), Seq("repo1", "repo2", "repo3"))

      val results = dataSource.getLatestSbtPluginVersions(curatedListOfSbtPluginConfigs)

      // 3 is for "sbtplugin1", "sbtplugin2" and "sbtplugin3"
      results.size shouldBe 3

      extractSbtPluginVersion(results, "sbtplugin1") shouldBe Some(Version(1, 0, 0))
      extractSbtPluginVersion(results, "sbtplugin2") shouldBe Some(Version(2, 0, 0))
      extractSbtPluginVersion(results, "sbtplugin3") shouldBe Some(Version(3, 0, 0))

    }

  }

  "GithubOpen" - {
    "should look at appDependencies.scala before build.sbt" in {

    }
  }

  private def prepareUnderTestClass(stubbedGithubs: Seq[Github], repositories: Seq[String]): DependenciesDataSource = {
    val mockedDependenciesConfig: ServiceDependenciesConfig = getMockedConfig()

    val dependenciesDataSource = new DependenciesDataSource(teamsAndRepositoriesStub(repositories), mockedDependenciesConfig, testTimestampGenerator) {
      override protected[servicedependencies] lazy val githubs = stubbedGithubs
    }
    dependenciesDataSource
  }


  private def teamsAndRepositoriesStub(repositories: Seq[String]) = new TeamsAndRepositoriesDataSource(mock[ServiceDependenciesConfig]) {
    override def getTeamsForRepository(repositoryName: String): Future[Seq[String]] = ???

    override def getTeamsForServices(): Future[Map[String, Seq[String]]] =
      Future.successful(serviceTeams)

    override def getAllRepositories(): Future[Seq[String]] =
      Future.successful(repositories)
  }


  private def getMockedConfig(): ServiceDependenciesConfig = {
    val mockedDependenciesConfig = mock[ServiceDependenciesConfig]
    val mockedGitApiConfig = mock[GitApiConfig]

    when(mockedDependenciesConfig.githubApiEnterpriseConfig).thenReturn(mockedGitApiConfig)
    when(mockedDependenciesConfig.githubApiOpenConfig).thenReturn(mockedGitApiConfig)

    when(mockedGitApiConfig.apiUrl).thenReturn("http://some.api.url")
    when(mockedGitApiConfig.key).thenReturn("key-12345")
    mockedDependenciesConfig
  }
}
