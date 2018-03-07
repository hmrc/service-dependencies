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

package uk.gov.hmrc.servicedependencies

import java.util.Base64

import org.eclipse.egit.github.core.client.{GitHubClient, RequestException}
import org.eclipse.egit.github.core.{RepositoryContents, RequestError}
import org.mockito.ArgumentMatchers.{any, eq => is}
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Matchers, OptionValues, WordSpec}
import uk.gov.hmrc.githubclient._
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, OtherDependencyConfig, SbtPluginConfig}
import uk.gov.hmrc.servicedependencies.model.Version

import scala.collection.JavaConverters._

class GithubSpec extends WordSpec with Matchers with MockitoSugar with OptionValues {

  "Finding multiple artifacts versions for a repository" should {

    "queries github's repository for plugins by looking in plugins.sbt" in new TestSetup {

      when(mockContentsService.getContents(any(), is(pluginsSbtFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Seq(
        SbtPluginConfig("bla", "sbt-plugin", None),
        SbtPluginConfig("bla", "sbt-auto-build", None)
      ), Nil, Nil)).right.get.sbtPlugins shouldBe
        Map("sbt-plugin" -> Some(Version(2, 3, 10)), "sbt-auto-build" -> Some(Version(1,3,0)))
    }

    "queries build.properties file(s) for sbt version" in new TestSetup {

      when(mockContentsService.getContents(any(), is(buildPropertiesFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/build.properties"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Seq(OtherDependencyConfig("sbt" , Some(Version(1,2,3)))))).right.get.others shouldBe
        Map("sbt" -> Some(Version(0, 13, 15)))
    }

    "queries github's repository for plugins and libraries" in new TestSetup {

      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(microServiceBuildFile)).asJava)

      when(mockContentsService.getContents(any(), is(pluginsSbtFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))).asJava)

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))).asJava)

      val results = github.findVersionsForMultipleArtifacts(repoName,
        CuratedDependencyConfig(Seq(
          SbtPluginConfig("bla", "sbt-plugin", None),
          SbtPluginConfig("bla", "sbt-auto-build", None)
        ), Seq(
          "play-ui",
          "play-health"
        ), Nil))

      results.right.get.sbtPlugins shouldBe Map("sbt-plugin" -> Some(Version(2, 3, 10)), "sbt-auto-build" -> Some(Version(1,3,0)))
      results.right.get.libraries shouldBe Map("play-ui" -> Some(Version(1, 3, 0)),  "play-health" -> Some(Version("0.5.0")))
    }


    "does not fail if the project folder does not exist" in new TestSetup {

      when(mockContentsService.getContents(any(), is("project")))
        .thenThrow(new RequestException(new RequestError(), 404))

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil)).right.get.libraries shouldBe
        Map("play-frontend" -> Some(Version(1, 1, 1)), "play-ui" -> Some(Version(2, 2, 2)), "play-health" -> Some(Version(8, 8, 8)))
    }


    "does not return an empty list of dependencies but fails when an http error occurs" in new TestSetup {

      private val exception = new RequestException(new RequestError(), 500)
      when(mockContentsService.getContents(any(), is("project")))
        .thenThrow(exception)

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil)).left.get shouldBe
        GithubSearchError("Unable to find dependencies for citizen-auth-frontend. Reason: 500", exception)
    }


    "return artifacts versions correctly for a repository's sbt.build file" in new TestSetup {

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil)).right.get.libraries shouldBe
        Map("play-frontend" -> Some(Version(1, 1, 1)), "play-ui" -> Some(Version(2, 2, 2)), "play-health" -> Some(Version(8, 8, 8)))
    }

    "return artifacts versions correctly for a repository's dependency file when there is more than one scala file under project" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(microServiceBuildFile), new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents().setContent("")).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_appDependencies.scala.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil)).right.get.libraries shouldBe
        Map("play-frontend" -> None, "play-ui" -> Some(Version(7, 4, 0)), "play-health" -> Some(Version(2, 1, 0)))
    }

    "return artifacts versions correctly for a repository's dependency file when there is scala files in project with no dependencies but an sbt.build file with dependencies" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents().setContent("")).asJava)

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil)).right.get.libraries shouldBe
        Map("play-frontend" -> Some(Version(1, 1, 1)), "play-ui" -> Some(Version(2, 2, 2)), "play-health" -> Some(Version(8, 8, 8)))
    }

    "return artifacts versions correctly for a repository's dependency file when there is scala files in project with dependencies and an build.sbt with no dependencies" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_appDependencies.scala.txt"))).asJava)

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents().setContent("")).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil)).right.get.libraries shouldBe
        Map("play-frontend" -> None, "play-ui" -> Some(Version(7, 4, 0)), "play-health" -> Some(Version(2, 1, 0)))
    }

    "return artifacts versions correctly for a repository's appDependencies.scala file" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_appDependencies.scala.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-frontend", "play-ui", "play-health"), Nil)).right.get.libraries shouldBe
        Map("play-frontend" -> None, "play-ui" -> Some(Version(7, 4, 0)), "play-health" -> Some(Version(2, 1, 0)))
    }

    "return None for artifacts that don't appear in the build file for a repository" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(microServiceBuildFile)).asJava)

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq("play-ui", "non-existing"), Nil)).right.get.libraries shouldBe
        Map("play-ui" -> Some(Version(1, 3, 0)), "non-existing" -> None)
    }

    "return empty map if curated config is empty passed in" in new TestSetup {

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Seq.empty[String], Nil)).right.get.libraries shouldBe
        Map.empty[String, Option[Version]]
    }
  }

  "Finding latest library version" should {
    val repoName = "some-cool-repo"


    "get the latest released library version correctly" in new TestSetup {
      when(mockedReleaseService.getTags("HMRC", repoName)).thenReturn(
        List(GhRepoTag( "release/1.10.1"),
          GhRepoTag( "release/1.10.100"),
          GhRepoTag( "release/2.10.19"),
          GhRepoTag( "release/4.10.19"),
          GhRepoTag( "release/4.10.2")))

      github.findLatestVersion(repoName).value shouldBe Version(4, 10, 19)
    }

    "ignore the tags that are not valid releases" in new TestSetup {
     
      when(mockedReleaseService.getTags("HMRC", repoName)).thenReturn(
        List(GhRepoTag("not-release/1.10.1"),
          GhRepoTag("release/2.10.19"),
          GhRepoTag("release/3.10.19"),
          GhRepoTag("not-release/4.10.3")))

      github.findLatestVersion(repoName).value shouldBe Version(3, 10, 19)
    }

    "handle RequestException(Not Found) case gracefully" in new TestSetup {
      object ReleaseServiceStub extends ReleaseService(mock[GitHubClient]) {
        override def getTags(org: String, repoName: String): List[GhRepoTag] =
          throw new RequestException(new RequestError, 404)
      }

      when(github.gh.releaseService).thenReturn(ReleaseServiceStub)

      github.findLatestVersion(repoName) shouldBe None
    }

    "other exceptions should not be handled gracefully" in new TestSetup {
      object ReleaseServiceStub extends ReleaseService(mock[GitHubClient]) {
        override def getTags(org: String, repoName: String): List[GhRepoTag] =
          throw new RequestException(new RequestError, 500)
      }

      when(github.gh.releaseService).thenReturn(ReleaseServiceStub)

      a [RequestException] should be thrownBy github.findLatestVersion(repoName)
    }
  }

  trait TestSetup {

    val microServiceBuildFile = "project/MicroServiceBuild.scala"
    val pluginsSbtFile = "project/plugins.sbt"
    val buildPropertiesFile = "project/build.properties"
    val buildSbtFile = "build.sbt"
    val appDependenciesFile = "project/AppDependencies.scala"
    val repoName = "citizen-auth-frontend"

    val github = new Github {
      override val tagPrefix = "release/"
      override val gh = mock[GithubApiClient]

      override def resolveTag(version: String) = s"$tagPrefix$version"

    }

    val mockContentsService = mock[ExtendedContentsService]
    when(github.gh.contentsService).thenReturn(mockContentsService)

    when(mockContentsService.getContents(any(), is("project")))
      .thenReturn(List[RepositoryContents]().asJava)

    when(mockContentsService.getContents(any(), is("build.sbt")))
      .thenReturn(List[RepositoryContents]().asJava)

    val mockedReleaseService = mock[ReleaseService]
    when(github.gh.releaseService).thenReturn(mockedReleaseService)
  }

  private def loadFileAsBase64String(filename: String): String = {
    val content = scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString
    Base64.getEncoder.withoutPadding().encodeToString(content.getBytes())
  }

}
