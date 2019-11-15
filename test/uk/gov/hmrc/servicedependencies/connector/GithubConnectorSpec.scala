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

package uk.gov.hmrc.servicedependencies.connector
import com.kenshoo.play.metrics.DisabledMetrics
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.{MustMatchers, WordSpec}
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.model.{GithubSearchResults, MongoRepositoryDependency, Version}
import uk.gov.hmrc.servicedependencies.util.DateUtil
import uk.gov.hmrc.servicedependencies.{Github, GithubSearchError}

class GithubConnectorSpec extends WordSpec with MustMatchers with MockitoSugar {

  val config = mock[ServiceDependenciesConfig]
  val metrics = new DisabledMetrics()

  val mockGithub = mock[Github]

  val githubConnector = new GithubConnector(mockGithub)


  "findPluginDependencies" should {

    "return a list of sbt dependencies" in {
      val search = new GithubSearchResults(
        sbtPlugins = Map("sbt-auto-build" -> Some(Version(1,2,3)), "sbt-artifactory" -> Some(Version(0,13,3))),
        libraries = Map.empty,
        others = Map.empty)

      val result = githubConnector.findPluginDependencies(search)
      result.length mustBe 2
      result(0) mustBe MongoRepositoryDependency("sbt-auto-build", Version(1,2,3))
      result(1) mustBe MongoRepositoryDependency("sbt-artifactory", Version(0,13,3))
    }

    "return an empty list when no sbt dependencies are present" in {
      val search = new GithubSearchResults(Map.empty, Map.empty, Map.empty)
      val result = githubConnector.findPluginDependencies(search)
      result.length mustBe 0
    }
  }

  "findLatestLibrariesVersions" should {

    "return a list of library version" in {
      val search = new GithubSearchResults(
        sbtPlugins = Map.empty,
        libraries  = Map("bootstrap-play" -> Some(Version(7,0,0)), "mongo-lock" -> Some(Version(1,4,1))),
        others     = Map.empty)


      val result = githubConnector.findLatestLibrariesVersions(search)
      result.length mustBe 2
      result(0) mustBe MongoRepositoryDependency("bootstrap-play", Version(7,0,0))
      result(1) mustBe MongoRepositoryDependency("mongo-lock", Version(1,4,1))
    }

    "return an empty list when no libraries are present" in {
      val search = new GithubSearchResults(Map.empty, Map.empty, Map.empty)
      val result = githubConnector.findLatestLibrariesVersions(search)
      result.length mustBe 0
    }
  }

  "findOtherDependencies" should {

    "return a list of library version" in {
      val search = new GithubSearchResults(
        sbtPlugins = Map.empty,
        libraries  = Map.empty,
        others     = Map("sbt" -> Some(Version(0,13,1))))

      val result = githubConnector.findOtherDependencies(search)
      result.length mustBe 1
      result.head mustBe MongoRepositoryDependency("sbt", Version(0,13,1))

    }

    "return an empty list when no libraries are present" in {
      val search = new GithubSearchResults(Map.empty, Map.empty, Map.empty)
      val result = githubConnector.findOtherDependencies(search)
      result.length mustBe 0
    }
  }

  "buildDependencies" should {

    val repoInfo = RepositoryInfo("test-repo", DateUtil.now, DateUtil.now, "Java")
    val depConfig = new CuratedDependencyConfig(Seq(), Seq(), Seq())

    "return None when repo is not found in github" in {

      when(mockGithub.findVersionsForMultipleArtifacts(any(), any()))
        .thenReturn(Left(GithubSearchError("mock error", new Exception("mock exception"))))

      githubConnector.buildDependencies(repoInfo, depConfig) mustBe None
    }

    "return all the dependencies wehn repo is in github" in {

      val mockResult = GithubSearchResults(
        sbtPlugins = Map("sbt-auto-build" -> Some(Version(1,2,3)), "sbt-artifactory" -> Some(Version(0,13,3))),
        libraries  = Map("bootstrap-play" -> Some(Version(7,0,0)), "mongo-lock" -> Some(Version(1,4,1))),
        others     = Map("sbt" -> Some(Version(0,13,1))))


      when(mockGithub.findVersionsForMultipleArtifacts(any(), any()))
        .thenReturn(Right(mockResult))

      val result = githubConnector.buildDependencies(repoInfo, depConfig)
      result.isDefined mustBe true
      result.get.sbtPluginDependencies mustBe Seq(MongoRepositoryDependency("sbt-auto-build", Version(1,2,3)), MongoRepositoryDependency("sbt-artifactory", Version(0,13,3)))
      result.get.libraryDependencies mustBe Seq(MongoRepositoryDependency("bootstrap-play", Version(7,0,0)), MongoRepositoryDependency("mongo-lock", Version(1,4,1)))
      result.get.otherDependencies mustBe Seq(MongoRepositoryDependency("sbt", Version(0,13,1)))

    }

  }

}
