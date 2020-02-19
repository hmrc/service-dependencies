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

package uk.gov.hmrc.servicedependencies.connector

import java.time.Instant

import com.kenshoo.play.metrics.DisabledMetrics
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig
import uk.gov.hmrc.servicedependencies.connector.model.RepositoryInfo
import uk.gov.hmrc.servicedependencies.model.{GithubDependency, GithubSearchResults, MongoRepositoryDependency, Version}
import uk.gov.hmrc.servicedependencies.{Github, GithubSearchError}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GithubConnectorSpec extends AnyWordSpec with Matchers with MockitoSugar {

  val config = mock[ServiceDependenciesConfig]
  val metrics = new DisabledMetrics()

  val mockGithub = mock[Github]

  val githubConnector = new GithubConnector(mockGithub)

  "buildDependencies" should {
    val repoInfo = RepositoryInfo("test-repo", Instant.now(), Instant.now(), "Java")

    "return None when repo is not found in github" in {
      when(mockGithub.findVersionsForMultipleArtifacts(any()))
        .thenReturn(Left(GithubSearchError("mock error", new Exception("mock exception"))))

      githubConnector.buildDependencies(repoInfo) mustBe None
    }

    "return all the dependencies when repo is in github" in {

      val mockResult = GithubSearchResults(
          sbtPlugins = Seq( GithubDependency(group = "uk.gov.hmrc"  , name = "sbt-auto-build" , version = Version("1.2.3"))
                          , GithubDependency(group = "uk.gov.hmrc"  , name = "sbt-artifactory", version = Version("0.13.3"))
                          )
        , libraries  = Seq( GithubDependency(group = "uk.gov.hmrc"  , name = "bootstrap-play" , version = Version("7.0.0"))
                          , GithubDependency(group = "uk.gov.hmrc"  , name = "mongo-lock"     , version = Version("1.4.1"))
                          )
        , others     = Seq( GithubDependency(group = "org.scala-sbt", name = "sbt"            , version = Version("0.13.1"))
                          )
        )


      when(mockGithub.findVersionsForMultipleArtifacts(any()))
        .thenReturn(Right(mockResult))

      val result = githubConnector.buildDependencies(repoInfo)
      result.isDefined mustBe true
      result.get.sbtPluginDependencies mustBe List( MongoRepositoryDependency(name = "sbt-auto-build" , group = "uk.gov.hmrc"  , currentVersion = Version(1,2,3))
                                                  , MongoRepositoryDependency(name = "sbt-artifactory", group = "uk.gov.hmrc"  , currentVersion = Version(0,13,3))
                                                  )
      result.get.libraryDependencies   mustBe List( MongoRepositoryDependency(name = "bootstrap-play" , group = "uk.gov.hmrc"  , currentVersion = Version(7,0,0))
                                                  , MongoRepositoryDependency(name = "mongo-lock"     , group = "uk.gov.hmrc"  , currentVersion = Version(1,4,1))
                                                  )
      result.get.otherDependencies     mustBe List( MongoRepositoryDependency(name = "sbt"             , group = "org.scala-sbt", currentVersion = Version(0,13,1)))
    }
  }
}
