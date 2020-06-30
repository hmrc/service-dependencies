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

package uk.gov.hmrc.servicedependencies.service

import java.time.{LocalDate, LocalDateTime}

import akka.Done
import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import play.api.cache.AsyncCacheApi
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, DependencyBobbyRule}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.LatestVersionRepository

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
        )

    def stubCuratedLibrariesOf(libraryNames: DependencyConfig*): Unit =
      when(mockServiceDependenciesConfig.curatedDependencyConfig)
        .thenReturn(aCuratedDependencyConfig(libraryNames.toList))

    def stubLatestLibraryVersionLookupSuccessfullyReturns(versionsByName: Seq[(SlugDependency, Version)]): Unit =
      when(mockLatestVersionRepository.getAllEntries)
        .thenReturn(
          Future.successful(
            versionsByName.map { case (sd, v) =>
              MongoLatestVersion(name = sd.artifact, group = sd.group, version = v)
            }
          )
        )

    def stubBobbyRulesViolations(bobbyRules: Map[(String, String), List[DependencyBobbyRule]]): Unit = {
      val bobbyRules2 = BobbyRules(
        bobbyRules.map { case ((g, a), rs) =>
          ((g, a), rs.map(r => BobbyRule(organisation = g, name = a, range = r.range, reason = r.reason, from = r.from)))
        }
      )
      when(mockServiceConfigsConnector.getBobbyRules)
        .thenReturn(Future.successful(bobbyRules2))
    }


    def stubNoEnrichmentsForDependencies(): Unit = {
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      stubBobbyRulesViolations(Map.empty)
    }

    def stubSlugVersionIsUnrecognised(name: String, version: String): Unit =
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
        Dependency(name = Dependency1.artifact, group = Dependency1.group, currentVersion = Version(Dependency1.version), latestVersion = None, bobbyRuleViolations = Nil),
        Dependency(name = Dependency3.artifact, group = Dependency3.group, currentVersion = Version(Dependency3.version), latestVersion = None, bobbyRuleViolations = Nil)
      )
    }

    "returning None when the slug is not recognised" in new Fixture {
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      when(mockSlugInfoService.getSlugInfo(SlugName, flag))
        .thenReturn(Future.successful(None))

      underTest.curatedLibrariesOfSlug(SlugName, flag).futureValue shouldBe None
    }

    "failing when slug retrieval encounters a failure" in new Fixture {
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      val failure = new RuntimeException("failed to retrieve slug info by flag")
      when(mockSlugInfoService.getSlugInfo(SlugName, flag))
        .thenReturn(Future.failed(failure))

      underTest.curatedLibrariesOfSlug(SlugName, flag).failed.futureValue shouldBe failure
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
          Dependency(name = Dependency1.artifact, group = Dependency1.group, currentVersion = Version(Dependency1.version), latestVersion = Some(LatestVersionOfDependency1), bobbyRuleViolations = Nil),
          Dependency(name = Dependency2.artifact, group = Dependency2.group, currentVersion = Version(Dependency2.version), latestVersion = None, bobbyRuleViolations = Nil),
          Dependency(name = Dependency3.artifact, group = Dependency3.group, currentVersion = Version(Dependency3.version), latestVersion = Some(LatestVersionOfDependency3), bobbyRuleViolations = Nil)
        )
      }

      "failing when retrieval of the latest library versions encounters a failure" in new Fixture {
        stubSlugVersionIsUnrecognised(SlugName, SlugVersion.toString)
        val failure = new RuntimeException("failed to retrieve latest library versions")

        when(mockSlugInfoService.getSlugInfo(SlugName, flag))
          .thenReturn(Future.successful(
            Some(slugInfo(
                withName         = SlugName
              , withVersion      = SlugVersion
              , withDependencies = List(Dependency2)
              ))
          ))

        when(mockLatestVersionRepository.getAllEntries)
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
          Dependency(name = Dependency1.artifact, group = Dependency1.group, currentVersion = Version(Dependency1.version), latestVersion = None, bobbyRuleViolations = List(bobbyRuleViolation1)),
          Dependency(name = Dependency2.artifact, group = Dependency2.group, currentVersion = Version(Dependency2.version), latestVersion = None, bobbyRuleViolations = Nil),
          Dependency(name = Dependency3.artifact, group = Dependency3.group, currentVersion = Version(Dependency3.version), latestVersion = None, bobbyRuleViolations = List(bobbyRuleViolation1, bobbyRuleViolation2))
        )
      }
    }
  }
}

private object SlugDependenciesServiceSpec {
  val SlugName = "a-slug-name"
  val SlugVersion = Version(major = 1, minor = 2, patch = 3)
  val Dependency1 = SlugDependency(path = "/path/dep1", version = "1.1.1", group = "uk.gov.hmrc"   , artifact = "artifact1")
  val Dependency2 = SlugDependency(path = "/path/dep2", version = "2.2.2", group = "com.test.group", artifact = "artifact2")
  val Dependency3 = SlugDependency(path = "/path/dep3", version = "3.3.3", group = "uk.gov.hmrc"   , artifact = "artifact3")
  val LatestVersionOfDependency1 = Version("1.2.0")
  val LatestVersionOfDependency3 = Version("3.4.0")

  def slugInfo(withName: String, withVersion: Version, withDependencies: List[SlugDependency]): SlugInfo =
    SlugInfo(
      uri               = "some-uri",
      created           = LocalDateTime.now(),
      name              = withName,
      version           = withVersion,
      teams             = Nil,
      runnerVersion     = "some-runner-version",
      classpath         = "some-classpath",
      java              = JavaInfo("some-java-version", "some-java-vendor", "some-java-kind"),
      dependencies      = withDependencies,
      applicationConfig = "some-application-config",
      slugConfig        = "some-slug-config",
      latest            = false,
      production        = true,
      qa                = false,
      staging           = true,
      development       = false,
      externalTest      = false,
      integration       = false
    )

  def aCuratedDependencyConfig(withLibraries: List[DependencyConfig]) =
    CuratedDependencyConfig(
      sbtPlugins = List.empty
    , libraries  = withLibraries
    , others     = List.empty
    )
}
