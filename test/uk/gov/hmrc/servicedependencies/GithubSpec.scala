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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.githubclient._
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.model.{GithubDependency, Version}

import scala.collection.JavaConverters._

class GithubSpec extends AnyWordSpec with Matchers with MockitoSugar with OptionValues {

  "Finding multiple artifacts versions for a repository" should {

    "query github's repository for plugins by looking in plugins.sbt" in new TestSetup {

      when(mockContentsService.getContents(any(), is(pluginsSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName).right.map(_.sbtPlugins.toList) shouldBe Right(List(
          GithubDependency(group = "uk.gov.hmrc"      , name = "sbt-auto-build"    , version = Version("1.3.0"))
        , GithubDependency(group = "uk.gov.hmrc"      , name = "sbt-git-versioning", version = Version("0.8.0"))
        , GithubDependency(group = "uk.gov.hmrc"      , name = "sbt-distributables", version = Version("0.9.0"))
        , GithubDependency(group = "com.typesafe.play", name = "sbt-plugin"        , version = Version("2.3.10"))
      ))
    }

    "query build.properties file(s) for sbt version" in new TestSetup {
      when(mockContentsService.getContents(any(), is(buildPropertiesFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/build.properties"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName).right.map(_.others.toList) shouldBe Right(List(
        GithubDependency(name = "sbt", group = "org.scala-sbt", version = Version("0.13.15"))
      ))
    }

    "parse build.properties file(s) containing other keys in addition to the sbt version" in new TestSetup {
      when(mockContentsService.getContents(any(), is(buildPropertiesFile)))
        .thenReturn(List(new RepositoryContents().setContent(loadFileAsBase64String("/github/multiplekey_build.properties"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName).right.map(_.others.toList) shouldBe Right(List(
        GithubDependency(name = "sbt", group = "org.scala-sbt", version = Version("0.13.17"))
      ))
    }

    "query github's repository for plugins and libraries" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(microServiceBuildFile)).asJava)

      when(mockContentsService.getContents(any(), is(pluginsSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_plugins_sbt_file_with_sbt_plugin.sbt.txt"))).asJava)

      when(mockContentsService.getContents(any(), is(microServiceBuildFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_build_file_with_play_frontend.sbt.txt"))).asJava)

      val results = github.findVersionsForMultipleArtifacts(repoName)

      results.right.map(_.sbtPlugins.toList) shouldBe Right(Seq(
          GithubDependency(group = "uk.gov.hmrc"      , name = "sbt-auto-build"    , version = Version("1.3.0"))
        , GithubDependency(group = "uk.gov.hmrc"      , name = "sbt-git-versioning", version = Version("0.8.0"))
        , GithubDependency(group = "uk.gov.hmrc"      , name = "sbt-distributables", version = Version("0.9.0"))
        , GithubDependency(group = "com.typesafe.play", name = "sbt-plugin"        , version = Version("2.3.10"))
        ))
      results.right.map(_.libraries.toList) shouldBe Right(Seq(
          GithubDependency(group = "com.codahale.metrics", name = "metrics-graphite", version = Version("3.0.1"))
        , GithubDependency(group = "com.kenshoo"         , name = "metrics-play"    , version = Version("2.3.0_0.1.6"))
        , GithubDependency(group = "org.scalatest"       , name = "scalatest"       , version = Version("2.2.1"))
        , GithubDependency(group = "org.pegdown"         , name = "pegdown"         , version = Version("1.4.2"))
        , GithubDependency(group = "org.jsoup"           , name = "jsoup"           , version = Version("1.7.2"))
        , GithubDependency(group = "uk.gov.hmrc"         , name = "play-ui"         , version = Version("1.3.0"))
        , GithubDependency(group = "uk.gov.hmrc"         , name = "play-health"     , version = Version("0.5.0"))
        , GithubDependency(group = "uk.gov.hmrc"         , name = "play-frontend"   , version = Version("10.2.0"))
        , GithubDependency(group = "uk.gov.hmrc"         , name = "emailaddress"    , version = Version("0.2.0"))
        , GithubDependency(group = "uk.gov.hmrc"         , name = "url-builder"     , version = Version("0.3.0"))
        , GithubDependency(group = "uk.gov.hmrc"         , name = "play-frontend"   , version = Version("10.2.0"))
        , GithubDependency(group = "uk.gov.hmrc"         , name = "hmrctest"        , version = Version("0.1.0"))
        ))
    }

    "not fail if the project folder does not exist" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenThrow(new RequestException(new RequestError(), 404))

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(repoName).right.map(_.libraries.toList) shouldBe Right(List(
            GithubDependency(group = "org.reactivemongo"      , name = "reactivemongo"          , version = Version("0.12.0"))
          , GithubDependency(group = "org.seleniumhq.selenium", name = "selenium-java"          , version = Version("2.43.1"))
          , GithubDependency(group = "org.seleniumhq.selenium", name = "selenium-firefox-driver", version = Version("2.43.1"))
          , GithubDependency(group = "com.typesafe.play"      , name = "play-json"              , version = Version("2.3.0"))
          , GithubDependency(group = "org.scalatest"          , name = "scalatest"              , version = Version("2.2.1"))
          , GithubDependency(group = "org.pegdown"            , name = "pegdown"                , version = Version("1.1.0"))
          , GithubDependency(group = "org.scala-lang"         , name = "scala-library"          , version = Version("2.10.4"))
          , GithubDependency(group = "org.scalaj"             , name = "scalaj-http"            , version = Version("1.1.0"))
          , GithubDependency(group = "info.cukes"             , name = "cucumber-scala_2.11"    , version = Version("1.1.8"))
          , GithubDependency(group = "info.cukes"             , name = "cucumber-junit"         , version = Version("1.1.8"))
          , GithubDependency(group = "info.cukes"             , name = "cucumber-picocontainer" , version = Version("1.1.8"))
          , GithubDependency(group = "junit"                  , name = "junit"                  , version = Version("4.11"))
          , GithubDependency(group = "com.novocode"           , name = "junit-interface"        , version = Version("0.10"))
          , GithubDependency(group = "uk.gov.hmrc"            , name = "scala-webdriver"        , version = Version("3.8.0"))
          , GithubDependency(group = "uk.gov.hmrc"            , name = "play-frontend"          , version = Version("1.1.1"))
          , GithubDependency(group = "uk.gov.hmrc"            , name = "play-ui"                , version = Version("2.2.2"))
          , GithubDependency(group = "uk.gov.hmrc"            , name = "play-health"            , version = Version("8.8.8"))
          ))
    }

    "not return an empty list of dependencies but fails when an http error occurs" in new TestSetup {
      private val exception = new RequestException(new RequestError(), 500)
      when(mockContentsService.getContents(any(), is("project")))
        .thenThrow(exception)

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(repoName) shouldBe Left(
          GithubSearchError("Unable to find dependencies for citizen-auth-frontend. Reason: 500", exception)
        )
    }

    "return artifacts versions correctly for a repository's build.sbt file" in new TestSetup {
      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github
        .findVersionsForMultipleArtifacts(repoName).right.map(_.libraries.toList) shouldBe Right(List(
            GithubDependency(group = "org.reactivemongo"      , name = "reactivemongo"          , version = Version("0.12.0"))
          , GithubDependency(group = "org.seleniumhq.selenium", name = "selenium-java"          , version = Version("2.43.1"))
          , GithubDependency(group = "org.seleniumhq.selenium", name = "selenium-firefox-driver", version = Version("2.43.1"))
          , GithubDependency(group = "com.typesafe.play"      , name = "play-json"              , version = Version("2.3.0"))
          , GithubDependency(group = "org.scalatest"          , name = "scalatest"              , version = Version("2.2.1"))
          , GithubDependency(group = "org.pegdown"            , name = "pegdown"                , version = Version("1.1.0"))
          , GithubDependency(group = "org.scala-lang"         , name = "scala-library"          , version = Version("2.10.4"))
          , GithubDependency(group = "org.scalaj"             , name = "scalaj-http"            , version = Version("1.1.0"))
          , GithubDependency(group = "info.cukes"             , name = "cucumber-scala_2.11"    , version = Version("1.1.8"))
          , GithubDependency(group = "info.cukes"             , name = "cucumber-junit"         , version = Version("1.1.8"))
          , GithubDependency(group = "info.cukes"             , name = "cucumber-picocontainer" , version = Version("1.1.8"))
          , GithubDependency(group = "junit"                  , name = "junit"                  , version = Version("4.11"))
          , GithubDependency(group = "com.novocode"           , name = "junit-interface"        , version = Version("0.10"))
          , GithubDependency(group = "uk.gov.hmrc"            , name = "scala-webdriver"        , version = Version("3.8.0"))
          , GithubDependency(group = "uk.gov.hmrc"            , name = "play-frontend"          , version = Version("1.1.1"))
          , GithubDependency(group = "uk.gov.hmrc"            , name = "play-ui"                , version = Version("2.2.2"))
          , GithubDependency(group = "uk.gov.hmrc"            , name = "play-health"            , version = Version("8.8.8"))
          ))
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

      github.findVersionsForMultipleArtifacts(repoName).right.map(_.libraries.toList) shouldBe Right(List(
          GithubDependency(group = "uk.gov.hmrc"   , name = "play-reactivemongo"         , version = Version("5.2.0"))
        , GithubDependency(group = "uk.gov.hmrc"   , name = "microservice-bootstrap"     , version = Version("5.16.0"))
        , GithubDependency(group = "uk.gov.hmrc"   , name = "play-health"                , version = Version("2.1.0-play-25"))
        , GithubDependency(group = "uk.gov.hmrc"   , name = "play-ui"                    , version = Version("7.4.0"))
        , GithubDependency(group = "uk.gov.hmrc"   , name = "play-config"                , version = Version("4.3.0"))
        , GithubDependency(group = "uk.gov.hmrc"   , name = "logback-json-logger"        , version = Version("3.1.0"))
        , GithubDependency(group = "uk.gov.hmrc"   , name = "domain"                     , version = Version("4.1.0"))
        , GithubDependency(group = "uk.gov.hmrc"   , name = "play-auth"                  , version = Version("1.2.0"))
        , GithubDependency(group = "org.typelevel" , name = "cats"                       , version = Version("0.9.0"))
        , GithubDependency(group = "uk.gov.hmrc"   , name = "hmrctest"                   , version = Version("2.3.0"))
        , GithubDependency(group = "org.scalatest" , name = "scalatest"                  , version = Version("2.2.6"))
        , GithubDependency(group = "org.pegdown"   , name = "pegdown"                    , version = Version("1.6.0"))
        , GithubDependency(group = "org.scalamock" , name = "scalamock-scalatest-support", version = Version("3.5.0"))
        , GithubDependency(group = "org.scalacheck", name = "scalacheck"                 , version = Version("1.13.4"))
        ))
    }

    "return artifacts versions correctly for a repository's dependency file when there is scala files in project with no dependencies but an sbt.build file with dependencies" in new TestSetup {
      when(mockContentsService.getContents(any(), is("project")))
        .thenReturn(List[RepositoryContents](new RepositoryContents().setPath(appDependenciesFile)).asJava)

      when(mockContentsService.getContents(any(), is(appDependenciesFile)))
        .thenReturn(List(new RepositoryContents().setContent("")).asJava)

      when(mockContentsService.getContents(any(), is(buildSbtFile)))
        .thenReturn(List(new RepositoryContents()
          .setContent(loadFileAsBase64String("/github/contents_sbt-build_file_with_play_frontend.build.txt"))).asJava)

      github.findVersionsForMultipleArtifacts(repoName).right.map(_.libraries.toList) shouldBe Right(List(
          GithubDependency(group = "org.reactivemongo"      , name = "reactivemongo"          , version = Version("0.12.0"))
        , GithubDependency(group = "org.seleniumhq.selenium", name = "selenium-java"          , version = Version("2.43.1"))
        , GithubDependency(group = "org.seleniumhq.selenium", name = "selenium-firefox-driver", version = Version("2.43.1"))
        , GithubDependency(group = "com.typesafe.play"      , name = "play-json"              , version = Version("2.3.0"))
        , GithubDependency(group = "org.scalatest"          , name = "scalatest"              , version = Version("2.2.1"))
        , GithubDependency(group = "org.pegdown"            , name = "pegdown"                , version = Version("1.1.0"))
        , GithubDependency(group = "org.scala-lang"         , name = "scala-library"          , version = Version("2.10.4"))
        , GithubDependency(group = "org.scalaj"             , name = "scalaj-http"            , version = Version("1.1.0"))
        , GithubDependency(group = "info.cukes"             , name = "cucumber-scala_2.11"    , version = Version("1.1.8"))
        , GithubDependency(group = "info.cukes"             , name = "cucumber-junit"         , version = Version("1.1.8"))
        , GithubDependency(group = "info.cukes"             , name = "cucumber-picocontainer" , version = Version("1.1.8"))
        , GithubDependency(group = "junit"                  , name = "junit"                  , version = Version("4.11"))
        , GithubDependency(group = "com.novocode"           , name = "junit-interface"        , version = Version("0.10"))
        , GithubDependency(group = "uk.gov.hmrc"            , name = "scala-webdriver"        , version = Version("3.8.0"))
        , GithubDependency(group = "uk.gov.hmrc"            , name = "play-frontend"          , version = Version("1.1.1"))
        , GithubDependency(group = "uk.gov.hmrc"            , name = "play-ui"                , version = Version("2.2.2"))
        , GithubDependency(group = "uk.gov.hmrc"            , name = "play-health"            , version = Version("8.8.8"))
        ))
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
