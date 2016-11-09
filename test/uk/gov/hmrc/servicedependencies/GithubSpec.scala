/*
 * Copyright 2016 HM Revenue & Customs
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

import org.eclipse.egit.github.core.{IRepositoryIdProvider, RepositoryContents}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, WordSpec}

import org.mockito.Mockito.when
import org.mockito.ArgumentMatchers._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.collection.JavaConverters._
import scala.collection.mutable

import uk.gov.hmrc.githubclient.{ExtendedContentsService, GithubApiClient}

class GithubSpec
  extends WordSpec
  with Matchers
  with MockitoSugar {

  val wireMock = new WireMockConfig(8081)

  private val firstBuildFile = "project/BuildFile.scala"
  private val secondBuildFile = "project/MicroserviceBuild.scala"
  private val pluginsSbtFile = "project/plugins.sbt"

  private val repoName = "citizen-auth-frontend"
  private val version = "2.2.0"

  private class TestGithub(artifact: String = "play-frontend", buildFilePaths: Seq[String] = Seq(firstBuildFile))
    extends Github(artifact, buildFilePaths) {

    override val gh = mock[GithubApiClient]
    override def resolveTag(version: String) = version
  }

  "Finding artifact version for a service" should {

    "queries github based on service name organisation by looking in plugins.sbt first" in {
      val githubService = new TestGithub ("sbt-plugin")
      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)

      stub.respond(pluginsSbtFile, loadFileAsString("/github/contents_plugins_sbt_file_with_sbt_plugin.json"))

      githubService.findArtifactVersion(repoName, Some(version)) shouldBe Some(Version(2, 3, 10))
    }

    "returns version number from build file if artifact name does not match in plugins.sbt" in {
      val githubService = new TestGithub()
      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)

      stub.respond(pluginsSbtFile, loadFileAsString("/github/contents_plugins_sbt_file_with_sbt_plugin.json"))
      stub.respond(firstBuildFile, loadFileAsString("/github/contents_build_file_with_play_frontend.json"))

      githubService.findArtifactVersion(repoName, Some(version)) shouldBe Some(Version(10, 2, 0))
    }

    "returns version number from build file if plugins.sbt is not found" in {
      val githubService = new TestGithub()
      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)

      stub.respond(firstBuildFile, loadFileAsString("/github/contents_build_file_with_play_frontend.json"))

      githubService.findArtifactVersion(repoName, Some(version)) shouldBe Some(Version(10, 2, 0))
    }

    "returns version number from second build file if artifact name does not match first build file" in {
      val githubService = new TestGithub(buildFilePaths = Seq(firstBuildFile, secondBuildFile))

      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)
      stub.respond(firstBuildFile, loadFileAsString("/github/contents_build_file_without_play_frontend.json"))
      stub.respond(secondBuildFile, loadFileAsString("/github/contents_build_file_with_play_frontend.json"))

      githubService.findArtifactVersion(repoName, Some(version)) shouldBe Some(Version(10, 2, 0))
    }

    "returns version number from second build file if first build file is not found" in {
      val githubService = new TestGithub(buildFilePaths = Seq(firstBuildFile, secondBuildFile))

      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)
      stub.respond(secondBuildFile, loadFileAsString("/github/contents_build_file_with_play_frontend.json"))

      githubService.findArtifactVersion(repoName, Some(version)) shouldBe Some(Version(10, 2, 0))
    }

    "returns None when no build files match the given artifact" in {
      val githubService = new TestGithub(buildFilePaths = Seq(firstBuildFile, secondBuildFile))

      val stub = attachRepoVersionContentsStub(githubService.gh, repoName, version)
      stub.respond(firstBuildFile, loadFileAsString("/github/contents_build_file_without_play_frontend.json"))
      stub.respond(secondBuildFile, loadFileAsString("/github/contents_build_file_without_play_frontend.json"))

      githubService.findArtifactVersion(repoName, Some(version)) shouldBe None
    }

    "returns None when service does not contain any of the build files specified" in {
      val githubService = new TestGithub (buildFilePaths = Seq(firstBuildFile, secondBuildFile))

      attachRepoVersionContentsStub(githubService.gh, repoName, version)

      githubService.findArtifactVersion("project-without-build-file", Some("2.3.4")) shouldBe None
    }

    "returns None when version passed in is None" in {
      val githubService = new TestGithub()

      attachRepoVersionContentsStub(githubService.gh, repoName, version)

      githubService.findArtifactVersion("tamc-pre-reg-frontend", None) shouldBe None
    }
  }

  case class GetContentsArgs(idProvider: IRepositoryIdProvider, filePath: String, ref: String)

  class RepoVersionContentsStub(repoName: String, version: String) {
    val responses = mutable.Queue[PartialFunction[(String, String, String), java.util.List[RepositoryContents]]]()

    def respond(filePath: String, content: String) = {
      responses.enqueue({
        case args if args == (s"HMRC/$repoName", filePath, s"$version") =>
          List(new RepositoryContents().setContent(content)).asJava
      })
    }

    val answer = buildAnswer3(GetContentsArgs.apply _) { a =>
        val x = asTuple(a)
        responses.collectFirst {
          case f if
          f.isDefinedAt(x) =>
            f(x)
        }.getOrElse(List[RepositoryContents]().asJava)
      }

    private def asTuple(args: GetContentsArgs): (String, String, String) =
      (args.idProvider.generateId(), args.filePath, args.ref)
  }

  private def attachRepoVersionContentsStub(gh: GithubApiClient, repoName: String, version: String) = {
    val mockContentsService = mock[ExtendedContentsService]
    when(gh.contentsService).thenReturn(mockContentsService)

    val stub = new RepoVersionContentsStub(repoName, version)
    when(mockContentsService.getContents(any(), any(), any())).thenAnswer(stub.answer)

    stub
  }

  private def loadFileAsString(filename: String): String = {
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename)).mkString
  }

  // NB -> This is a workaround for a bug in Mockito whereby a test file can't contain more than one captor of the same type
  def buildAnswer3[A, B, C, ARGS, RESULT](function: (A, B, C) => ARGS)(result: ARGS => RESULT) = {
    new Answer[RESULT] {
      override def answer(invocationOnMock: InvocationOnMock): RESULT = {
        val rawArgs = invocationOnMock.getArguments

        val stage1 = convert(rawArgs) {
          function.curried
        }
        val stage2 = convert(rawArgs.tail) {
          stage1
        }
        val stage3 = convert(rawArgs.tail.tail) {
          stage2
        }

        result(stage3)
      }

      def convert[I, O](args: Seq[AnyRef])(fn: I => O): O = {
        fn(args.head.asInstanceOf[I])
      }
    }
  }
}
