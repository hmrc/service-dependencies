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

package uk.gov.hmrc.servicedependencies

import java.util.Base64

import org.eclipse.egit.github.core.client.{GitHubClient, RequestException}
import org.eclipse.egit.github.core.{RepositoryContents, RequestError}
import org.mockito.ArgumentMatchers.{any, eq => is}
import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import uk.gov.hmrc.githubclient._
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.model.Version

import scala.collection.JavaConverters._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GithubSpec extends AnyWordSpec with Matchers with MockitoSugar with OptionValues {

  "Finding multiple artifacts versions for a repository" should {

    "queries github's repository for plugins by looking in plugins.sbt" in new TestSetup {

      when(mockContentsService.getContents(any(), is(pluginsSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(
          repoName,
          CuratedDependencyConfig(
            sbtPlugins        = List(
                                  DependencyConfig(name = "sbt-plugin"    , group = "com.typesafe.play", latestVersion = None)
                                , DependencyConfig(name = "sbt-auto-build", group = "uk.gov.hmrc"      , latestVersion = None)
                                )
          , libraries         = Nil
          , otherDependencies = Nil
          )
        )
        .right
        .get
        .sbtPlugins shouldBe
        Map(
          ("sbt-plugin"    , "com.typesafe.play") -> Some(Version("2.3.10")),
          ("sbt-auto-build", "uk.gov.hmrc"      ) -> Some(Version("1.3.0")))
    }

    "queries build.properties file(s) for sbt version" in new TestSetup {
      val curatedDependencyConfig = CuratedDependencyConfig(
          sbtPlugins        = Nil
        , libraries         = Nil
        , otherDependencies = List(DependencyConfig(name = "sbt", group = "org.scala-sbt", latestVersion = Some(Version(1, 2, 3))))
        )
      when(mockContentsService.getContents(any(), is(buildPropertiesFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/build.properties"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, curatedDependencyConfig).right.get.others shouldBe Map(
        ("sbt", "org.scala-sbt") -> Some(Version("0.13.15"))
      )
    }

    "parses build.properties file(s) containing other keys in addition to the sbt version" in new TestSetup {
      val curatedDependencyConfig = CuratedDependencyConfig(
          sbtPlugins        = Nil
        , libraries         = Nil
        , otherDependencies = List(DependencyConfig(name = "sbt", group = "org.scala-sbt", latestVersion = Some(Version(1, 2, 3))))
        )
      when(mockContentsService.getContents(any(), is(buildPropertiesFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/multiplekey_build.properties"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName, curatedDependencyConfig).right.get.others shouldBe Map(
        ("sbt", "org.scala-sbt") -> Some(Version("0.13.17"))
      )
    }

    "queries github's repository for plugins and libraries" in new TestSetup {

      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(microServiceBuildFile)).asJava)

      when(mockContentsService.getContents(any(), is(pluginsSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))).asJava)

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))).asJava)

      val results = github.findVersionsForMultipleArtifacts(
        repoName,
        CuratedDependencyConfig(
            sbtPlugins        = List(
                                  DependencyConfig(name = "sbt-plugin"    , group = "com.typesafe.play", latestVersion = None)
                                , DependencyConfig(name = "sbt-auto-build", group = "uk.gov.hmrc"      , latestVersion = None)
                                )
          , libraries         = List(
                                  DependencyConfig(name = "play-ui"    , group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-health", group = "uk.gov.hmrc", latestVersion = None)
                                )
          , otherDependencies = Nil
          )
      )

      results.right.get.sbtPlugins shouldBe Map(
          ("sbt-plugin"    , "com.typesafe.play") -> Some(Version("2.3.10"))
        , ("sbt-auto-build", "uk.gov.hmrc"      ) -> Some(Version("1.3.0"))
        )
      results.right.get.libraries shouldBe Map(
          ("play-ui"    , "uk.gov.hmrc") -> Some(Version("1.3.0"))
        , ("play-health", "uk.gov.hmrc") -> Some(Version("0.5.0"))
        )
    }

    "does not fail if the project folder does not exist" in new TestSetup {

      when(mockContentsService.getContents(any(), is("project")))
        .thenThrow(new RequestException(new RequestError(), 404))

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(
          repoName,
          CuratedDependencyConfig(
            sbtPlugins        = Nil
          , libraries         = List(
                                  DependencyConfig(name = "play-frontend", group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-ui"      , group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-health"  , group = "uk.gov.hmrc", latestVersion = None)
                                )
          , otherDependencies = Nil
          )
         )
        .right
        .get
        .libraries shouldBe
        Map(
          ("play-frontend", "uk.gov.hmrc") -> Some(Version("1.1.1")),
          ("play-ui"      , "uk.gov.hmrc") -> Some(Version("2.2.2")),
          ("play-health"  , "uk.gov.hmrc") -> Some(Version("8.8.8")))
    }

    "does not return an empty list of dependencies but fails when an http error occurs" in new TestSetup {

      private val exception = new RequestException(new RequestError(), 500)
      when(mockContentsService.getContents(any(), is("project")))
        .thenThrow(exception)

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(
          repoName,
          CuratedDependencyConfig(
            sbtPlugins        = Nil
          , libraries         = List(
                                  DependencyConfig(name = "play-frontend", group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-ui"      , group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-health"  , group = "uk.gov.hmrc", latestVersion = None)
                                )
          , otherDependencies = Nil
          )
        )
        .left
        .get shouldBe
        GithubSearchError("Unable to find dependencies for citizen-auth-frontend. Reason: 500", exception)
    }

    "return artifacts versions correctly for a repository's sbt.build file" in new TestSetup {

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(
          repoName,
          CuratedDependencyConfig(
             sbtPlugins       = Nil
           , libraries        = List(
                                  DependencyConfig(name = "play-frontend", group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-ui"      , group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-health"  , group = "uk.gov.hmrc", latestVersion = None)
                                )
          , otherDependencies = Nil
          )
        )
        .right
        .get
        .libraries shouldBe
        Map(
          ("play-frontend", "uk.gov.hmrc") -> Some(Version("1.1.1"))
        , ("play-ui"      , "uk.gov.hmrc") -> Some(Version("2.2.2"))
        , ("play-health"  , "uk.gov.hmrc") -> Some(Version("8.8.8"))
        )
    }

    "return artifacts versions correctly for a repository's dependency file when there is more than one scala file under project" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(
          List[RepositoryContents](
            new RepositoryContents().setPath(microServiceBuildFile),
            new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents().setContent("")).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_appDependencies.scala.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(
          repoName,
          CuratedDependencyConfig(
            sbtPlugins       = Nil
          , libraries        = List(
                                 DependencyConfig(name = "play-frontend", group = "uk.gov.hmrc", latestVersion = None)
                               , DependencyConfig(name = "play-ui"      , group = "uk.gov.hmrc", latestVersion = None)
                               , DependencyConfig(name = "play-health"  , group = "uk.gov.hmrc", latestVersion = None)
                               )
          , otherDependencies = Nil
          )
        )
        .right
        .get
        .libraries shouldBe
        Map(
          ("play-frontend", "uk.gov.hmrc") -> None
        , ("play-ui"      , "uk.gov.hmrc") -> Some(Version("7.4.0"))
        , ("play-health"  , "uk.gov.hmrc") -> Some(Version("2.1.0-play-25"))
        )
    }

    "return artifacts versions correctly for a repository's dependency file when there is scala files in project with no dependencies but an sbt.build file with dependencies" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents().setContent("")).asJava)

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(
          repoName,
          CuratedDependencyConfig(
            sbtPlugins        = Nil
          , libraries         = List(
                                  DependencyConfig(name = "play-frontend", group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-ui"      , group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-health"  , group = "uk.gov.hmrc", latestVersion = None)
                                )
          , otherDependencies = Nil
          )
        )
        .right
        .get
        .libraries shouldBe
        Map(
          ("play-frontend", "uk.gov.hmrc") -> Some(Version("1.1.1"))
        , ("play-ui"      , "uk.gov.hmrc") -> Some(Version("2.2.2"))
        , ("play-health"  , "uk.gov.hmrc") -> Some(Version("8.8.8"))
        )
    }

    "return artifacts versions correctly for a repository's dependency file when there is scala files in project with dependencies and an build.sbt with no dependencies" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_appDependencies.scala.txt"))).asJava)

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents().setContent("")).asJava)

      github
        .findVersionsForMultipleArtifacts(
          repoName,
          CuratedDependencyConfig(
            sbtPlugins        = Nil
          , libraries         = List(
                                  DependencyConfig(name = "play-frontend", group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-ui"      , group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-health"  , group = "uk.gov.hmrc", latestVersion = None)
                                )
          , otherDependencies = Nil
          )
        )
        .right
        .get
        .libraries shouldBe
        Map(
          ("play-frontend", "uk.gov.hmrc") -> None
        , ("play-ui"      , "uk.gov.hmrc") -> Some(Version("7.4.0"))
        , ("play-health"  , "uk.gov.hmrc") -> Some(Version("2.1.0-play-25"))
        )
    }

    "return artifacts versions correctly for a repository's appDependencies.scala file" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_appDependencies.scala.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(
          repoName,
          CuratedDependencyConfig(
            sbtPlugins        = Nil
          , libraries         = List(
                                  DependencyConfig(name = "play-frontend", group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-ui"      , group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "play-health"  , group = "uk.gov.hmrc", latestVersion = None)
                                )
          , otherDependencies = Nil
          )
        )
        .right
        .get
        .libraries shouldBe
        Map(
          ("play-frontend", "uk.gov.hmrc") -> None
        , ("play-ui"      , "uk.gov.hmrc") -> Some(Version(7, 4, 0))
        , ("play-health"  , "uk.gov.hmrc") -> Some(Version("2.1.0-play-25"))
        )
    }

    "return None for artifacts that don't appear in the build file for a repository" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(microServiceBuildFile)).asJava)

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(
            repoName
          , CuratedDependencyConfig(
            sbtPlugins        = Nil
          , libraries         = List(
                                  DependencyConfig(name = "play-ui"     , group = "uk.gov.hmrc", latestVersion = None)
                                , DependencyConfig(name = "non-existing", group = "uk.gov.hmrc", latestVersion = None)
                                )
          , otherDependencies = Nil
          )
        )
        .right
        .get
        .libraries shouldBe
        Map(
           ("play-ui"     , "uk.gov.hmrc") -> Some(Version("1.3.0"))
         , ("non-existing", "uk.gov.hmrc") -> None
         )
    }

    "return empty map if curated config is empty passed in" in new TestSetup {

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(repoName, CuratedDependencyConfig(Nil, Nil, Nil))
        .right
        .get
        .libraries shouldBe
        Map.empty[(String, String), Option[Version]]
    }
  }

  trait TestSetup {

    val microServiceBuildFile = "project/MicroServiceBuild.scala"
    val pluginsSbtFile        = "project/plugins.sbt"
    val buildPropertiesFile   = "project/build.properties"
    val buildSbtFile          = "build.sbt"
    val appDependenciesFile   = "project/AppDependencies.scala"
    val repoName              = "citizen-auth-frontend"

    val mockContentsService = mock[ExtendedContentsService]

    when(mockContentsService.getContents(any(), is("project")))
      .thenReturn(List[RepositoryContents]().asJava)

    when(mockContentsService.getContents(any(), is("build.sbt")))
      .thenReturn(List[RepositoryContents]().asJava)

    val mockedReleaseService = mock[ReleaseService]

    val github = new Github(mockedReleaseService, mockContentsService)
  }

  private def loadFileAsBase64String(filename: String): String = {
    val content = scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString
    Base64.getEncoder.withoutPadding().encodeToString(content.getBytes())
  }
}
