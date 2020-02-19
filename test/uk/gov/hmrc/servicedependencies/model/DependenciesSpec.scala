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

package uk.gov.hmrc.servicedependencies.model

import java.time.{Instant, LocalDate}

import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}

import scala.concurrent.Future

class DependenciesSpec
    extends AsyncFlatSpec
    with Matchers
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience {

  behavior of "Dependencies.enrichWithBobbyRuleViolations"

  it should "keep dependencies the same when no bobby rules for that dependency exist" in {

    val bobbyRules = BobbyRules(Map(
      ("uk.gov.hmrc", "name") -> List(buildRule("(,2.5.19)"))
    ))

    val dependency   = buildDependency("unmatched-name", "2.5.18")
    val dependencies = Dependencies("repo", Seq(dependency), Seq(dependency), Seq(dependency), Instant.now())

    dependencies.enrichWithBobbyRuleViolations(bobbyRules) shouldBe dependencies
  }

  it should "keep dependencies the same when the version is not in a bobby rule range" in {
    val bobbyRules = BobbyRules(Map(
      ("uk.gov.hmrc", "name") -> List(buildRule("(,2.5.19)"))
    ))

    val dependency   = buildDependency("name", "2.5.19")
    val dependencies = Dependencies("repo", Seq(dependency), Seq(dependency), Seq(dependency), Instant.now())

    dependencies.enrichWithBobbyRuleViolations(bobbyRules) shouldBe dependencies
  }

  it should "add a violation when a rule matches the dependency" in {
    val bobbyRule1 = buildRule("(,2.5.19)")
    val bobbyRule2 = buildRule("(,2.5.17)")
    val bobbyRules = BobbyRules(Map(
        ("uk.gov.hmrc", "name") -> List(bobbyRule1, bobbyRule2)
      ))

    val dependency   = buildDependency("name", "2.5.18")
    val dependencies = Dependencies("repo", Seq(dependency), Seq(dependency), Seq(dependency), Instant.now())

    val result = dependencies.enrichWithBobbyRuleViolations(bobbyRules)

    val expected = dependency.copy(bobbyRuleViolations = List(bobbyRule1.asDependencyBobbyRule))

    result.repositoryName         shouldBe dependencies.repositoryName
    result.libraryDependencies    shouldBe Seq(expected)
    result.sbtPluginsDependencies shouldBe Seq(expected)
    result.otherDependencies      shouldBe Seq(expected)
    result.lastUpdated            shouldBe dependencies.lastUpdated
  }

  it should "add multiple violation when multiple rules matches the dependency" in {
    val bobbyRule1 = buildRule("(,2.5.19)")
    val bobbyRule2 = buildRule("(,2.5.17)")
    val bobbyRules = BobbyRules(Map(
        ("uk.gov.hmrc", "name") -> List(bobbyRule1, bobbyRule2)
      ))

    val dependency   = buildDependency("name", "2.5.16")
    val dependencies = Dependencies("repo", Seq(dependency), Seq(dependency), Seq(dependency), Instant.now())

    val result = dependencies.enrichWithBobbyRuleViolations(bobbyRules)

    val expected = dependency.copy(
      bobbyRuleViolations = List(
        bobbyRule1.asDependencyBobbyRule,
        bobbyRule2.asDependencyBobbyRule
      ))

    result.repositoryName         shouldBe dependencies.repositoryName
    result.libraryDependencies    shouldBe Seq(expected)
    result.sbtPluginsDependencies shouldBe Seq(expected)
    result.otherDependencies      shouldBe Seq(expected)
    result.lastUpdated            shouldBe dependencies.lastUpdated
  }

  it should "handle multiple dependencies and bobby rules" in {
    val bobbyRule1 = buildRule("(,2.5.19)")
    val bobbyRule2 = buildRule("(,2.5.17)")
    val bobbyRule3 = buildRule("(,2.4.0)")
    val bobbyRule4 = buildRule("(,2.5.17)")

    val bobbyRules = BobbyRules(Map(
      ("uk.gov.hmrc", "name"        ) -> List(bobbyRule1, bobbyRule2),
      ("uk.gov.hmrc", "another-name") -> List(bobbyRule3),
      ("uk.gov.hmrc", "unmatched"   ) -> List(bobbyRule4)
    ))

    val libraryDependency = buildDependency("name", "2.5.18")
    val pluginDependency  = buildDependency("another-name", "2.3")
    val dependencies      = Dependencies("repo", Seq(libraryDependency), Seq(pluginDependency), Seq(), Instant.now())

    val result = dependencies.enrichWithBobbyRuleViolations(bobbyRules)

    val expectedLibraryDependency =
      libraryDependency.copy(bobbyRuleViolations = List(bobbyRule1.asDependencyBobbyRule))

    val expectedPluginDependency = pluginDependency.copy(bobbyRuleViolations = List(bobbyRule3.asDependencyBobbyRule))

    result.repositoryName         shouldBe dependencies.repositoryName
    result.libraryDependencies    shouldBe Seq(expectedLibraryDependency)
    result.sbtPluginsDependencies shouldBe Seq(expectedPluginDependency)
    result.otherDependencies      shouldBe Seq()
    result.lastUpdated            shouldBe dependencies.lastUpdated
  }

  private def buildRule(range: String) =
    BobbyRule(
      organisation = "hmrc"
    , name         = "name"
    , range        = BobbyVersionRange(range)
    , reason       = "reason"
    , from         = LocalDate.now()
    )

  private def buildDependency(name: String, version: String) =
    Dependency(
      name                = name
    , group               = "uk.gov.hmrc"
    , currentVersion      = Version(version)
    , latestVersion       = None
    , bobbyRuleViolations = List()
    )
}
