/*
 * Copyright 2023 HM Revenue & Customs
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

import java.time.{LocalDate, Instant}
import akka.Done
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import play.api.cache.AsyncCacheApi
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, DependencyBobbyRule, ImportedBy}
import uk.gov.hmrc.servicedependencies.model.DependencyScope.Compile
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.LatestVersionRepository
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser.{Arrow, DependencyGraph, Node}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

class SlugDependenciesServiceSpec extends AnyFreeSpec with MockitoSugar with Matchers with ScalaFutures with OptionValues {

  import SlugDependenciesServiceSpec._

  private trait Fixture {
    val mockSlugInfoService           = mock[SlugInfoService]
    val mockServiceDependenciesConfig = mock[ServiceDependenciesConfig]
    val mockLatestVersionRepository   = mock[LatestVersionRepository]
    val mockServiceConfigsConnector   = mock[ServiceConfigsConnector]
    val mockGraphParser               = mock[DependencyGraphParser]

    val mockCache = new AsyncCacheApi {
      override def set(key: String, value: Any, expiration: Duration): Future[Done] = Future.successful(Done)
      override def remove(key: String): Future[Done] = Future.successful(Done)
      override def getOrElseUpdate[A](key: String, expiration: Duration)(orElse: => Future[A])(implicit evidence$1: ClassTag[A]): Future[A] = orElse
      override def get[T](key: String)(implicit evidence$2: ClassTag[T]): Future[Option[T]] = Future.successful(None)
      override def removeAll(): Future[Done] = Future.successful(Done)
    }

    val underTest =
      new SlugDependenciesService(
          mockSlugInfoService
        , mockServiceDependenciesConfig
        , mockLatestVersionRepository
        , mockServiceConfigsConnector
        , mockGraphParser
        )

    def stubCuratedLibrariesOf(libraryNames: DependencyConfig*): Unit =
      when(mockServiceDependenciesConfig.curatedDependencyConfig)
        .thenReturn(aCuratedDependencyConfig(libraryNames.toList))

    def stubLatestLibraryVersionLookupSuccessfullyReturns(versionsByName: Seq[(SlugDependency, Version)]): Unit =
      when(mockLatestVersionRepository.getAllEntries())
        .thenReturn(
          Future.successful(
            versionsByName.map { case (sd, v) =>
              LatestVersion(name = sd.artifact, group = sd.group, version = v)
            }
          )
        )

    def stubBobbyRulesViolations(bobbyRules: Map[(String, String), List[DependencyBobbyRule]]): Unit = {
      val bobbyRules2 = BobbyRules(
        bobbyRules.map { case ((g, a), rs) =>
          ((g, a), rs.map(r => BobbyRule(organisation = g, name = a, range = r.range, reason = r.reason, from = r.from)))
        }
      )
      when(mockServiceConfigsConnector.getBobbyRules())
        .thenReturn(Future.successful(bobbyRules2))
    }


    def stubNoEnrichmentsForDependencies(): Unit = {
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      stubBobbyRulesViolations(Map.empty)
    }

    def stubSlugVersionIsUnrecognised(name: String, version: Version): Unit =
      when(mockSlugInfoService.getSlugInfo(name, version))
        .thenReturn(Future.successful(None))
  }

  "SlugDependenciesService" - {
    val flag = SlugInfoFlag.Latest

    "returning only curated libraries when the slug is recognised" in new Fixture {
      stubCuratedLibrariesOf(
          DependencyConfig(name = Dependency1.artifact, group = Dependency1.group, latestVersion = None)
        , DependencyConfig(name = Dependency3.artifact, group = Dependency3.group, latestVersion = None)
        )

      stubNoEnrichmentsForDependencies()

      when(mockSlugInfoService.getSlugInfo(SlugName, flag))
        .thenReturn(
          Future.successful(
            Some(slugInfo(
                withName         = SlugName
              , withVersion      = SlugVersion
              , withDependencies = List(Dependency1, Dependency2, Dependency3))
              )
          )
        )

      underTest.curatedLibrariesOfSlug(SlugName, flag).futureValue.value should contain theSameElementsAs Seq(
        Dependency(name = Dependency1.artifact, group = Dependency1.group, currentVersion = Dependency1.version, latestVersion = None, bobbyRuleViolations = Nil, scope = Some(Compile)),
        Dependency(name = Dependency3.artifact, group = Dependency3.group, currentVersion = Dependency3.version, latestVersion = None, bobbyRuleViolations = Nil, scope = Some(Compile))
      )
    }

    "returning None when the slug is not recognised" in new Fixture {
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)

      when(mockServiceConfigsConnector.getBobbyRules())
        .thenReturn(Future.successful(BobbyRules(Map.empty)))

      when(mockSlugInfoService.getSlugInfo(SlugName, flag))
        .thenReturn(Future.successful(None))

      underTest.curatedLibrariesOfSlug(SlugName, flag).futureValue shouldBe None
    }

    "enriches dependencies with latest version information" - {
      "only adding the latest version when known" in new Fixture {
        stubBobbyRulesViolations(Map.empty)

        stubCuratedLibrariesOf(
          DependencyConfig(name = Dependency1.artifact, group = Dependency1.group, latestVersion = None)
        , DependencyConfig(name = Dependency2.artifact, group = Dependency2.group, latestVersion = None)
        , DependencyConfig(name = Dependency3.artifact, group = Dependency3.group, latestVersion = None)
        )

        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq(
            Dependency1 -> LatestVersionOfDependency1
          , Dependency3 -> LatestVersionOfDependency3
          ))

        when(mockSlugInfoService.getSlugInfo(SlugName, flag))
          .thenReturn(
            Future.successful(
              Some(slugInfo(
                  withName         = SlugName
                , withVersion      = SlugVersion
                , withDependencies = List(Dependency1, Dependency2, Dependency3)
              ))
            )
          )

        underTest.curatedLibrariesOfSlug(SlugName, flag).futureValue.value should contain theSameElementsAs Seq(
          Dependency(name = Dependency1.artifact, group = Dependency1.group, currentVersion = Dependency1.version, latestVersion = Some(LatestVersionOfDependency1), bobbyRuleViolations = Nil, scope = Some(Compile)),
          Dependency(name = Dependency2.artifact, group = Dependency2.group, currentVersion = Dependency2.version, latestVersion = None, bobbyRuleViolations = Nil, scope = Some(Compile)),
          Dependency(name = Dependency3.artifact, group = Dependency3.group, currentVersion = Dependency3.version, latestVersion = Some(LatestVersionOfDependency3), bobbyRuleViolations = Nil, scope = Some(Compile))
        )
      }

      "failing when retrieval of the latest library versions encounters a failure" in new Fixture {
        stubSlugVersionIsUnrecognised(SlugName, SlugVersion)
        val failure = new RuntimeException("failed to retrieve latest library versions")

        when(mockSlugInfoService.getSlugInfo(SlugName, flag))
          .thenReturn(Future.successful(
            Some(slugInfo(
                withName         = SlugName
              , withVersion      = SlugVersion
              , withDependencies = List(Dependency2)
              ))
          ))

        when(mockServiceConfigsConnector.getBobbyRules())
          .thenReturn(Future.successful(BobbyRules(Map.empty)))

        when(mockLatestVersionRepository.getAllEntries())
          .thenReturn(Future.failed(failure))

        underTest.curatedLibrariesOfSlug(SlugName, flag).failed.futureValue shouldBe failure
      }
    }

    "enriches dependencies with Bobby rule violations" - {
      "only adding rule violations when there are active violations" in new Fixture {
        stubCuratedLibrariesOf(
            DependencyConfig(name = Dependency1.artifact, group = Dependency1.group, latestVersion = None)
          , DependencyConfig(name = Dependency2.artifact, group = Dependency2.group, latestVersion = None)
          , DependencyConfig(name = Dependency3.artifact, group = Dependency3.group, latestVersion = None)
          )
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        val bobbyRuleViolation1 = DependencyBobbyRule(reason = "a reason"      , from = LocalDate.now(), range = BobbyVersionRange("(,6.6.6)"))
        val bobbyRuleViolation2 = DependencyBobbyRule(reason = "another reason", from = LocalDate.now(), range = BobbyVersionRange("(,9.9.9)"))
        stubBobbyRulesViolations(Map(
          (Dependency1.group, Dependency1.artifact) -> List(bobbyRuleViolation1)
        , (Dependency3.group, Dependency3.artifact) -> List(bobbyRuleViolation1, bobbyRuleViolation2)
        ))

        when(mockSlugInfoService.getSlugInfo(SlugName, flag))
          .thenReturn(
            Future.successful(
              Some(slugInfo(
                  withName         = SlugName
                , withVersion      = SlugVersion
                , withDependencies = List(Dependency1, Dependency2, Dependency3)
                ))
            )
          )

        underTest.curatedLibrariesOfSlug(SlugName, flag).futureValue.value should contain theSameElementsAs Seq(
          Dependency(name = Dependency1.artifact, group = Dependency1.group, currentVersion = Dependency1.version, latestVersion = None, bobbyRuleViolations = List(bobbyRuleViolation1), scope = Some(Compile)),
          Dependency(name = Dependency2.artifact, group = Dependency2.group, currentVersion = Dependency2.version, latestVersion = None, bobbyRuleViolations = Nil, scope = Some(Compile)),
          Dependency(name = Dependency3.artifact, group = Dependency3.group, currentVersion = Dependency3.version, latestVersion = None, bobbyRuleViolations = List(bobbyRuleViolation1, bobbyRuleViolation2), scope = Some(Compile))
        )
      }
    }
  }


  "gets dependencies from graphs where available" in new Fixture {
    // Add stub to fix NullPointerException
    stubCuratedLibrariesOf(DependencyConfig(name = Dependency1.artifact, group = Dependency1.group, latestVersion = None))

    val rootNode       = Node("root:root:1.0.0")
    val nodeHmrc       = Node("uk.gov.hmrc:hmrc-mongo:1.1.0")
    val nodeNonHmrc    = Node("org.foo:bar:1.0.6")
    val nodeTransitive = Node("org.foo:baz:1.1.1")

    val nodes = Set(nodeHmrc, nodeNonHmrc, nodeTransitive)
    val arrows = Set(
      Arrow(from = rootNode, to = nodeHmrc),
      Arrow(from = rootNode, to = nodeNonHmrc),
      Arrow(from = nodeNonHmrc, to =  nodeTransitive)
    )

    val graph = DependencyGraph(nodes, arrows)
    when(mockGraphParser.parse(any[String])).thenReturn(graph)

    stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)

    val bobbyRuleViolation1 = DependencyBobbyRule(reason = "a reason", from = LocalDate.now(), range = BobbyVersionRange("(,6.6.6)"))
    stubBobbyRulesViolations(Map(
      (nodeNonHmrc.group, nodeNonHmrc.artefact) -> List(bobbyRuleViolation1)
    ))

    when(mockSlugInfoService.getSlugInfo(SlugName, SlugInfoFlag.Latest))
      .thenReturn(
        Future.successful(
          Some(slugInfo(
              withName         = SlugName
            , withVersion      = SlugVersion
            , withDependencies = List.empty
            ).copy(dependencyDotCompile = "non-blank value, wont actually be parsed as we're mocking the response")
          )
        )
      )

    underTest.curatedLibrariesOfSlug(SlugName, SlugInfoFlag.Latest).futureValue.value.filter(_.scope.contains(Compile)) should contain theSameElementsAs Seq(
        Dependency(name = nodeHmrc.artefact, group = nodeHmrc.group, currentVersion = Version(nodeHmrc.version), latestVersion = None, bobbyRuleViolations = Nil, importBy = None, scope = Some(Compile))
      , Dependency(name = nodeNonHmrc.artefact, group = nodeNonHmrc.group, currentVersion = Version(nodeNonHmrc.version), latestVersion = None, bobbyRuleViolations = List(bobbyRuleViolation1), scope = Some(Compile))
    )
  }


  "include parent of transitive dependency that violation bobby rule" in new Fixture {
    // Add stub to fix NullPointerException
    stubCuratedLibrariesOf(DependencyConfig(name = Dependency1.artifact, group = Dependency1.group, latestVersion = None))

    val rootNode       = Node("root:root:1.0.0")
    val nodeHmrc       = Node("uk.gov.hmrc:hmrc-mongo:1.1.0")
    val nodeNonHmrc    = Node("org.foo:bar:1.0.6")
    val nodeTransitive = Node("org.foo:baz:1.1.1")

    val nodes = Set(nodeHmrc, nodeNonHmrc, nodeTransitive)
    val arrows = Set(
      Arrow(from = rootNode, to = nodeHmrc),
      Arrow(from = rootNode, to = nodeNonHmrc),
      Arrow(from = nodeNonHmrc, to =  nodeTransitive)
    )

    val graph = DependencyGraph(nodes, arrows)
    when(mockGraphParser.parse(any[String])).thenReturn(graph)

    stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)

    val bobbyRuleViolation1 = DependencyBobbyRule(reason = "a reason", from = LocalDate.now(), range = BobbyVersionRange("(,6.6.6)"))
    stubBobbyRulesViolations(Map(
      (nodeTransitive.group, nodeTransitive.artefact) -> List(bobbyRuleViolation1)
    ))

    when(mockSlugInfoService.getSlugInfo(SlugName, SlugInfoFlag.Latest))
      .thenReturn(
        Future.successful(
          Some(slugInfo(
              withName         = SlugName
            , withVersion      = SlugVersion
            , withDependencies = List.empty
          ).copy(dependencyDotCompile = "non-blank value, wont actually be parsed as we're mocking the response")
          )
        )
      )

    underTest.curatedLibrariesOfSlug(SlugName, SlugInfoFlag.Latest).futureValue.value.filter(_.scope.contains(Compile)) should contain theSameElementsAs Seq(
      Dependency(name = nodeHmrc.artefact, group = nodeHmrc.group, currentVersion = Version(nodeHmrc.version), latestVersion = None, bobbyRuleViolations = Nil, importBy = None, scope = Some(Compile))
      , Dependency(name = nodeNonHmrc.artefact, group = nodeNonHmrc.group, currentVersion = Version(nodeNonHmrc.version), latestVersion = None, bobbyRuleViolations = Nil, scope = Some(Compile))
      , Dependency(name = nodeTransitive.artefact, group = nodeTransitive.group, currentVersion = Version(nodeTransitive.version), latestVersion = None, bobbyRuleViolations = List(bobbyRuleViolation1),
        importBy = Some(ImportedBy(nodeNonHmrc.artefact, nodeNonHmrc.group, Version(nodeNonHmrc.version))), scope = Some(Compile))
    )
  }
}

private object SlugDependenciesServiceSpec {
  val SlugName = "a-slug-name"
  val SlugVersion = Version(major = 1, minor = 2, patch = 3)
  val Dependency1 = SlugDependency(path = "/path/dep1", version = Version("1.1.1"), group = "uk.gov.hmrc"   , artifact = "artifact1")
  val Dependency2 = SlugDependency(path = "/path/dep2", version = Version("2.2.2"), group = "com.test.group", artifact = "artifact2")
  val Dependency3 = SlugDependency(path = "/path/dep3", version = Version("3.3.3"), group = "uk.gov.hmrc"   , artifact = "artifact3")
  val LatestVersionOfDependency1 = Version("1.2.0")
  val LatestVersionOfDependency3 = Version("3.4.0")

  def slugInfo(withName: String, withVersion: Version, withDependencies: List[SlugDependency]): SlugInfo =
    SlugInfo(
      uri                   = "some-uri",
      created               = Instant.now(),
      name                  = withName,
      version               = withVersion,
      teams                 = Nil,
      runnerVersion         = "some-runner-version",
      classpath             = "some-classpath",
      java                  = JavaInfo("some-java-version", "some-java-vendor", "some-java-kind"),
      sbtVersion            = Some("1.4.9"),
      repoUrl               = Some("https://github.com/hmrc/test.git"),
      dependencies          = withDependencies,
      dependencyDotCompile  = "",
      dependencyDotProvided = "",
      dependencyDotTest     = "",
      dependencyDotIt       = "",
      dependencyDotBuild    = "",
      applicationConfig     = "some-application-config",
      slugConfig            = "some-slug-config"
    )

  def aCuratedDependencyConfig(withLibraries: List[DependencyConfig]) =
    CuratedDependencyConfig(
      sbtPlugins = List.empty
    , libraries  = withLibraries
    , others     = List.empty
    )
}
