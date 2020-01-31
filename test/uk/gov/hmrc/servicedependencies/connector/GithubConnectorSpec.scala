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
import uk.gov.hmrc.servicedependencies.model.{GithubSearchResults, MongoRepositoryDependency, Version}
import uk.gov.hmrc.servicedependencies.{Github, GithubSearchError}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GithubConnectorSpec extends AnyWordSpec with Matchers with MockitoSugar {

  val config = mock[ServiceDependenciesConfig]
  val metrics = new DisabledMetrics()

  val mockGithub = mock[Github]

  val githubConnector = new GithubConnector(mockGithub)

  "toMongoRepositoryDependencies" should {

    "return a list of sbt dependencies" in {
      val sbtPlugins = Map( ("sbt-auto-build" , "uk.gov.hmrc") -> Some(Version(1,2,3))
                          , ("sbt-artifactory", "uk.gov.hmrc") -> Some(Version(0,13,3))
                          )

      val result = githubConnector.toMongoRepositoryDependencies(sbtPlugins)
      result.length mustBe 2
      result(0) mustBe MongoRepositoryDependency(name = "sbt-auto-build" , group = "uk.gov.hmrc", currentVersion = Version(1,2,3))
      result(1) mustBe MongoRepositoryDependency(name = "sbt-artifactory", group = "uk.gov.hmrc", currentVersion = Version(0,13,3))
    }

    "return an empty list when no sbt dependencies are present" in {
      githubConnector.toMongoRepositoryDependencies(Map.empty) mustBe Nil
    }
  }

  "buildDependencies" should {

    val repoInfo = RepositoryInfo("test-repo", Instant.now(), Instant.now(), "Java")
    val depConfig = new CuratedDependencyConfig(Seq(), Seq(), Seq())

    "return None when repo is not found in github" in {

      when(mockGithub.findVersionsForMultipleArtifacts(any(), any()))
        .thenReturn(Left(GithubSearchError("mock error", new Exception("mock exception"))))

      githubConnector.buildDependencies(repoInfo, depConfig) mustBe None
    }

    "return all the dependencies wehn repo is in github" in {

      val mockResult = GithubSearchResults(
          sbtPlugins = Map( ("sbt-auto-build" , "uk.gov.hmrc"  ) -> Some(Version(1,2,3))
                          , ("sbt-artifactory", "uk.gov.hmrc"  ) -> Some(Version(0,13,3))
                          )
        , libraries  = Map( ("bootstrap-play" , "uk.gov.hmrc"  ) -> Some(Version(7,0,0))
                          , ("mongo-lock"     , "uk.gov.hmrc"  ) -> Some(Version(1,4,1))
                          )
        , others     = Map( ("sbt"            , "org.scala-sbt") -> Some(Version(0,13,1)))
        )


      when(mockGithub.findVersionsForMultipleArtifacts(any(), any()))
        .thenReturn(Right(mockResult))

      val result = githubConnector.buildDependencies(repoInfo, depConfig)
      result.isDefined mustBe true
      result.get.sbtPluginDependencies mustBe Seq( MongoRepositoryDependency(name = "sbt-auto-build" , group = "uk.gov.hmrc"  , currentVersion = Version(1,2,3))
                                                 , MongoRepositoryDependency(name = "sbt-artifactory", group = "uk.gov.hmrc"  , currentVersion = Version(0,13,3))
                                                 )
      result.get.libraryDependencies   mustBe Seq( MongoRepositoryDependency(name = "bootstrap-play" , group = "uk.gov.hmrc"  , currentVersion = Version(7,0,0))
                                                 , MongoRepositoryDependency(name = "mongo-lock"     , group = "uk.gov.hmrc"  , currentVersion = Version(1,4,1))
                                                 )
      result.get.otherDependencies     mustBe Seq(MongoRepositoryDependency(name = "sbt"             , group = "org.scala-sbt", currentVersion = Version(0,13,1)))
    }
  }
}
