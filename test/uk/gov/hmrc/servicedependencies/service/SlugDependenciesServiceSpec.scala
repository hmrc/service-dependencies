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

package uk.gov.hmrc.servicedependencies.service

import java.time.{LocalDate, LocalDateTime}

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Matchers, OptionValues}
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.servicedependencies.config.CuratedDependencyConfigProvider
import uk.gov.hmrc.servicedependencies.config.model.CuratedDependencyConfig
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, DependencyBobbyRule}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.LibraryVersionRepository
import uk.gov.hmrc.servicedependencies.service.SlugDependenciesService.TargetVersion.{Labelled, Latest}

import scala.concurrent.Future

class SlugDependenciesServiceSpec extends FreeSpec with MockitoSugar with Matchers with ScalaFutures with OptionValues {

  import SlugDependenciesServiceSpec._

  private trait Fixture {
    val slugInfoService = mock[SlugInfoService]
    val curatedDependencyConfigProvider = mock[CuratedDependencyConfigProvider]
    val libraryVersionRepository = mock[LibraryVersionRepository]
    val serviceConfigsService = mock[ServiceConfigsService]

    val underTest = new SlugDependenciesService(slugInfoService, curatedDependencyConfigProvider,
      libraryVersionRepository, serviceConfigsService)

    def stubCuratedLibrariesOf(libraryNames: String*): Unit =
      when(curatedDependencyConfigProvider.curatedDependencyConfig).thenReturn(
        aCuratedDependencyConfig(libraryNames)
      )

    def stubLatestLibraryVersionLookupSuccessfullyReturns(versionsByName: Seq[(String, Version)]): Unit =
      when(libraryVersionRepository.getAllEntries).thenReturn(
        Future.successful(
          versionsByName.map { case (n, v) =>
            MongoLibraryVersion(libraryName = n, version = Some(v))
          }
        )
      )

    def stubBobbyRulesViolations(dependencies: List[Dependency], violations: List[List[DependencyBobbyRule]]): Unit = {
      val enrichedDependencies = dependencies.zip(violations).map { case (dependency, violations) =>
        dependency.copy(bobbyRuleViolations = violations)
      }

      when(serviceConfigsService.getDependenciesWithBobbyRules(dependencies)).thenReturn(
        Future.successful(enrichedDependencies)
      )
    }

    def stubNoBobbyRulesViolations(): Unit = {
      val withArgumentAsFutureSuccess = new Answer[Future[List[Dependency]]] {
        private val ArgIndex = 0
        override def answer(invocation: InvocationOnMock): Future[List[Dependency]] = {
          val dependencies = invocation.getArgument[List[Dependency]](ArgIndex)
          Future.successful(dependencies)
        }
      }

      when(serviceConfigsService.getDependenciesWithBobbyRules(any[List[Dependency]]())).thenAnswer(withArgumentAsFutureSuccess)
    }

    def stubNoEnrichmentsForDependencies(): Unit = {
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      stubNoBobbyRulesViolations()
    }

    def stubSlugVersionIsUnrecognised(name: String, version: String): Unit =
      when(slugInfoService.getSlugInfo(name, version)).thenReturn(
        Future.successful(None)
      )
  }

  "SlugDependenciesService" - {

    "retrieves slug info by flag when the 'latest' version is requested" - {
      "returning only curated libraries when the slug is recognised" in new Fixture {
        stubCuratedLibrariesOf(Dependency1.artifact, Dependency3.artifact)
        stubNoEnrichmentsForDependencies()
        when(slugInfoService.getSlugInfo(SlugName, flag = SlugInfoFlag.Latest)).thenReturn(
          Future.successful(
            Some(slugInfo(withName = SlugName, withVersion = SlugVersion.toString, withDependencies = List(
              Dependency1, Dependency2, Dependency3
            )))
          )
        )

        underTest.curatedLibrariesOfSlug(SlugName, atVersion = Latest).futureValue.value should contain theSameElementsAs Seq(
          Dependency(name = Dependency1.artifact, currentVersion = Version(Dependency1.version), latestVersion = None, bobbyRuleViolations = Nil),
          Dependency(name = Dependency3.artifact, currentVersion = Version(Dependency3.version), latestVersion = None, bobbyRuleViolations = Nil)
        )
      }

      "returning None when the slug is not recognised" in new Fixture {
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        when(slugInfoService.getSlugInfo(SlugName, flag = SlugInfoFlag.Latest)).thenReturn(
          Future.successful(None)
        )

        underTest.curatedLibrariesOfSlug(SlugName, atVersion = Latest).futureValue shouldBe None
      }

      "failing when slug retrieval encounters a failure" in new Fixture {
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        val failure = new RuntimeException("failed to retrieve slug info by flag")
        when(slugInfoService.getSlugInfo(SlugName, flag = SlugInfoFlag.Latest)).thenReturn(
          Future.failed(failure)
        )

        underTest.curatedLibrariesOfSlug(SlugName, atVersion = Latest).failed.futureValue shouldBe failure
      }
    }

    "retrieves slug info by version when a specific version is requested" - {
      "returning only curated libraries when the target slug is recognised" in new Fixture {
        stubCuratedLibrariesOf(Dependency2.artifact, Dependency3.artifact)
        stubNoEnrichmentsForDependencies()
        when(slugInfoService.getSlugInfo(SlugName, version = SlugVersion.toString)).thenReturn(
          Future.successful(
            Some(slugInfo(withName = SlugName, withVersion = SlugVersion.toString, withDependencies = List(
              Dependency1, Dependency2, Dependency3
            )))
          )
        )

        underTest.curatedLibrariesOfSlug(SlugName, atVersion = Labelled(SlugVersion)).futureValue.value should contain theSameElementsAs Seq(
          Dependency(name = Dependency2.artifact, currentVersion = Version(Dependency2.version), latestVersion = None, bobbyRuleViolations = Nil),
          Dependency(name = Dependency3.artifact, currentVersion = Version(Dependency3.version), latestVersion = None, bobbyRuleViolations = Nil)
        )
      }

      "returning None when the slug is not recognised" in new Fixture {
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        stubSlugVersionIsUnrecognised(SlugName, SlugVersion.toString)

        underTest.curatedLibrariesOfSlug(SlugName, atVersion = Labelled(SlugVersion)).futureValue shouldBe None
      }

      "failing when slug retrieval encounters a failure" in new Fixture {
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        val failure = new RuntimeException("failed to retrieve slug info by version")
        when(slugInfoService.getSlugInfo(SlugName, version = SlugVersion.toString)).thenReturn(
          Future.failed(failure)
        )

        underTest.curatedLibrariesOfSlug(SlugName, atVersion = Labelled(SlugVersion)).failed.futureValue shouldBe failure
      }
    }

    "enriches dependencies with latest version information" - {
      "only adding the latest version when known" in new Fixture {
        stubNoBobbyRulesViolations()
        stubCuratedLibrariesOf(Dependency1.artifact, Dependency2.artifact, Dependency3.artifact)
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq(
          Dependency1.artifact -> LatestVersionOfDependency1, Dependency3.artifact -> LatestVersionOfDependency3
        ))
        when(slugInfoService.getSlugInfo(SlugName, version = SlugVersion.toString)).thenReturn(
          Future.successful(
            Some(slugInfo(withName = SlugName, withVersion = SlugVersion.toString, withDependencies = List(
              Dependency1, Dependency2, Dependency3
            )))
          )
        )

        underTest.curatedLibrariesOfSlug(SlugName, atVersion = Labelled(SlugVersion)).futureValue.value should contain theSameElementsAs Seq(
          Dependency(name = Dependency1.artifact, currentVersion = Version(Dependency1.version), latestVersion = Some(LatestVersionOfDependency1), bobbyRuleViolations = Nil),
          Dependency(name = Dependency2.artifact, currentVersion = Version(Dependency2.version), latestVersion = None, bobbyRuleViolations = Nil),
          Dependency(name = Dependency3.artifact, currentVersion = Version(Dependency3.version), latestVersion = Some(LatestVersionOfDependency3), bobbyRuleViolations = Nil)
        )
      }

      "failing when retrieval of the latest library versions encounters a failure" in new Fixture {
        stubSlugVersionIsUnrecognised(SlugName, SlugVersion.toString)
        val failure = new RuntimeException("failed to retrieve latest library versions")
        when(libraryVersionRepository.getAllEntries).thenReturn(
          Future.failed(failure)
        )

        underTest.curatedLibrariesOfSlug(SlugName, Labelled(SlugVersion)).failed.futureValue shouldBe failure
      }
    }

    "enriches dependencies with Bobby rule violations" - {
      "only adding rule violations when there are active violations" in new Fixture {
        stubCuratedLibrariesOf(Dependency1.artifact, Dependency2.artifact, Dependency3.artifact)
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        val BobbyRuleViolation1 = DependencyBobbyRule(reason = "a reason", from = LocalDate.now(), range = BobbyVersionRange("(,6.6.6)"))
        val BobbyRuleViolation2 = DependencyBobbyRule(reason = "another reason", from = LocalDate.now(), range = BobbyVersionRange("(,9.9.9)"))
        stubBobbyRulesViolations(
          dependencies = List(
            Dependency(name = Dependency1.artifact, currentVersion = Version(Dependency1.version), latestVersion = None, bobbyRuleViolations = Nil),
            Dependency(name = Dependency2.artifact, currentVersion = Version(Dependency2.version), latestVersion = None, bobbyRuleViolations = Nil),
            Dependency(name = Dependency3.artifact, currentVersion = Version(Dependency3.version), latestVersion = None, bobbyRuleViolations = Nil)
          ),
          violations = List(
            List(BobbyRuleViolation1),
            Nil,
            List(BobbyRuleViolation1, BobbyRuleViolation2)
          )
        )

        when(slugInfoService.getSlugInfo(SlugName, version = SlugVersion.toString)).thenReturn(
          Future.successful(
            Some(slugInfo(withName = SlugName, withVersion = SlugVersion.toString, withDependencies = List(
              Dependency1, Dependency2, Dependency3
            )))
          )
        )

        underTest.curatedLibrariesOfSlug(SlugName, Labelled(SlugVersion)).futureValue.value should contain theSameElementsAs Seq(
          Dependency(name = Dependency1.artifact, currentVersion = Version(Dependency1.version), latestVersion = None,
            bobbyRuleViolations = List(BobbyRuleViolation1)),
          Dependency(name = Dependency2.artifact, currentVersion = Version(Dependency2.version), latestVersion = None,
            bobbyRuleViolations = Nil),
          Dependency(name = Dependency3.artifact, currentVersion = Version(Dependency3.version), latestVersion = None,
            bobbyRuleViolations = List(BobbyRuleViolation1, BobbyRuleViolation2))
        )
      }

      "failing when the retrieval or application of Bobby rules encounters a failure" in new Fixture {
        stubCuratedLibrariesOf(Dependency1.artifact, Dependency2.artifact, Dependency3.artifact)
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        when(slugInfoService.getSlugInfo(SlugName, version = SlugVersion.toString)).thenReturn(
          Future.successful(
            Some(slugInfo(withName = SlugName, withVersion = SlugVersion.toString, withDependencies = List(
              Dependency1, Dependency2, Dependency3
            )))
          )
        )
        val failure = new RuntimeException("failed to apply bobby rules")
        when(serviceConfigsService.getDependenciesWithBobbyRules(any[List[Dependency]])).thenReturn(
          Future.failed(failure)
        )

        underTest.curatedLibrariesOfSlug(SlugName, atVersion = Labelled(SlugVersion)).failed.futureValue shouldBe failure
      }
    }
  }
}

private object SlugDependenciesServiceSpec {
  val SlugName = "a-slug-name"
  val SlugVersion = Version(major = 1, minor = 2, patch = 3)
  val Dependency1 = SlugDependency(path = "/path/dep1", version = "1.1.1", group = "com.test.group", artifact = "artifact1")
  val Dependency2 = SlugDependency(path = "/path/dep2", version = "2.2.2", group = "com.test.group", artifact = "artifact2")
  val Dependency3 = SlugDependency(path = "/path/dep3", version = "3.3.3", group = "com.test.group", artifact = "artifact3")
  val LatestVersionOfDependency1 = Version("1.2.0")
  val LatestVersionOfDependency3 = Version("3.4.0")

  def slugInfo(withName: String, withVersion: String, withDependencies: List[SlugDependency]): SlugInfo =
    SlugInfo(
      uri = "some-uri",
      created = LocalDateTime.now(),
      withName,
      Version(withVersion),
      teams = Nil,
      runnerVersion = "some-runner-version",
      classpath = "some-classpath",
      java = JavaInfo("some-java-version", "some-java-vendor", "some-java-kind"),
      dependencies = withDependencies,
      applicationConfig = "some-application-config",
      slugConfig = "some-slug-config",
      latest = false,
      production = true,
      qa = false,
      staging = true,
      development = false,
      externalTest = false,
      integration = false
    )

  def aCuratedDependencyConfig(withLibraries: Seq[String]): CuratedDependencyConfig =
    CuratedDependencyConfig(
      sbtPlugins = Seq.empty,
      withLibraries,
      otherDependencies = Seq.empty
    )
}