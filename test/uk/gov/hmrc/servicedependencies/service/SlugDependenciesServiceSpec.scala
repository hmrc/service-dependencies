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
import uk.gov.hmrc.servicedependencies.persistence.{LibraryVersionRepository, SlugInfoRepository}

import scala.concurrent.Future

class SlugDependenciesServiceSpec extends FreeSpec with MockitoSugar with Matchers with ScalaFutures with OptionValues {

  import SlugDependenciesServiceSpec._

  private trait Fixture {
    val slugInfoRepository = mock[SlugInfoRepository]
    val curatedDependencyConfigProvider = mock[CuratedDependencyConfigProvider]
    val libraryVersionRepository = mock[LibraryVersionRepository]
    val serviceConfigsService = mock[ServiceConfigsService]

    val underTest = new SlugDependenciesService(slugInfoRepository, curatedDependencyConfigProvider,
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
  }

  "SlugDependenciesService" - {

    "retrieves only the dependencies of a known slug that are curated dependencies" - {
      "enriched with bobby rules violations" in new Fixture {
        val dependency1PreViolationEnrichment = Dependency(name = Dependency1.artifact,
          currentVersion = Version(Dependency1.version), latestVersion = None, bobbyRuleViolations = Nil)
        val dependency3PreViolationEnrichment = Dependency(name = Dependency3.artifact,
          currentVersion = Version(Dependency3.version), latestVersion = None, bobbyRuleViolations = Nil)
        val dependency1RuleViolation = DependencyBobbyRule(reason = "some reason", from = LocalDate.now(), range = BobbyVersionRange("(,9.9.9)"))
        stubBobbyRulesViolations(
          dependencies = List(dependency1PreViolationEnrichment, dependency3PreViolationEnrichment),
          violations = List(List(dependency1RuleViolation), Nil)
        )
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        stubCuratedLibrariesOf(Dependency1.artifact, Dependency3.artifact)
        when(slugInfoRepository.getSlugInfos(SlugName, Some(SlugVersion))).thenReturn(
          Future.successful(Seq(slugInfo(withName = SlugName, withVersion = SlugVersion,
            withDependencies = List(Dependency1, Dependency2, Dependency3))
          ))
        )

        underTest.curatedLibrariesOfSlug(SlugName, SlugVersion).futureValue.value should contain theSameElementsAs Seq(
          dependency1PreViolationEnrichment.copy(bobbyRuleViolations = List(dependency1RuleViolation)),
          dependency3PreViolationEnrichment
        )
      }

      "enriched with latest versions when known" in new Fixture {
        stubNoBobbyRulesViolations()
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq(
          Dependency1.artifact -> LatestVersionOfDependency1, Dependency3.artifact -> LatestVersionOfDependency3
        ))
        stubCuratedLibrariesOf(Dependency1.artifact, Dependency3.artifact)
        when(slugInfoRepository.getSlugInfos(SlugName, Some(SlugVersion))).thenReturn(
          Future.successful(Seq(slugInfo(withName = SlugName, withVersion = SlugVersion,
            withDependencies = List(Dependency1, Dependency2, Dependency3))
          ))
        )

        underTest.curatedLibrariesOfSlug(SlugName, SlugVersion).futureValue.value should contain theSameElementsAs Seq(
          Dependency(name = Dependency1.artifact, currentVersion = Version(Dependency1.version), latestVersion = Some(LatestVersionOfDependency1), bobbyRuleViolations = Nil),
          Dependency(name = Dependency3.artifact, currentVersion = Version(Dependency3.version), latestVersion = Some(LatestVersionOfDependency3), bobbyRuleViolations = Nil)
        )
      }

      "without latest versions when not known" in new Fixture {
        stubNoBobbyRulesViolations()
        stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
        stubCuratedLibrariesOf(Dependency1.artifact, Dependency3.artifact)
        when(slugInfoRepository.getSlugInfos(SlugName, Some(SlugVersion))).thenReturn(
          Future.successful(Seq(slugInfo(withName = SlugName, withVersion = SlugVersion,
            withDependencies = List(Dependency1, Dependency2, Dependency3))
          ))
        )

        underTest.curatedLibrariesOfSlug(SlugName, SlugVersion).futureValue.value should contain theSameElementsAs Seq(
          Dependency(name = Dependency1.artifact, currentVersion = Version(Dependency1.version), latestVersion = None, bobbyRuleViolations = Nil),
          Dependency(name = Dependency3.artifact, currentVersion = Version(Dependency3.version), latestVersion = None, bobbyRuleViolations = Nil)
        )
      }
    }

    "indicates that the requested slug could not be found by returning None" in new Fixture {
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      when(slugInfoRepository.getSlugInfos(SlugName, Some(SlugVersion))).thenReturn(
        Future.successful(Seq.empty)
      )

      underTest.curatedLibrariesOfSlug(SlugName, SlugVersion).futureValue shouldBe None
    }

    "propagates any failure to retrieve the latest versions of libraries" in new Fixture {
      val failure = new RuntimeException("failed to retrieve latest library versions")
      when(libraryVersionRepository.getAllEntries).thenReturn(
        Future.failed(failure)
      )
      when(slugInfoRepository.getSlugInfos(SlugName, Some(SlugVersion))).thenReturn(
        Future.successful(Seq.empty)
      )

      underTest.curatedLibrariesOfSlug(SlugName, SlugVersion).failed.futureValue shouldBe failure
    }

    "propagates any failure to retrieve slug infos" in new Fixture {
      val failure = new RuntimeException("failed to retrieve slug infos")
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      when(slugInfoRepository.getSlugInfos(SlugName, Some(SlugVersion))).thenReturn(
        Future.failed(failure)
      )

      underTest.curatedLibrariesOfSlug(SlugName, SlugVersion).failed.futureValue shouldBe failure
    }

    "propagates any failure to determine bobby rule violations" in new Fixture {
      stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      when(slugInfoRepository.getSlugInfos(SlugName, Some(SlugVersion))).thenReturn(
        Future.successful(Seq(slugInfo(withName = SlugName, withVersion = SlugVersion,
          withDependencies = List(Dependency1, Dependency2, Dependency3))
        ))
      )
      stubCuratedLibrariesOf(Dependency1.artifact, Dependency3.artifact)
      val failure = new RuntimeException("failed to apply bobby rules")
      when(serviceConfigsService.getDependenciesWithBobbyRules(any[List[Dependency]])).thenReturn(
        Future.failed(failure)
      )

      underTest.curatedLibrariesOfSlug(SlugName, SlugVersion).failed.futureValue shouldBe failure
    }
  }
}

private object SlugDependenciesServiceSpec {
  val SlugName = ""
  val SlugVersion = "1.2.3"
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