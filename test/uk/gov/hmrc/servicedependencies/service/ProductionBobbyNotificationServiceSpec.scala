/*
 * Copyright 2025 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedBobbyReportRepository
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.connector.SlackNotificationsConnector
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.time.temporal.ChronoUnit.DAYS
import uk.gov.hmrc.servicedependencies.model.BobbyReport
import uk.gov.hmrc.servicedependencies.model.Version
import uk.gov.hmrc.servicedependencies.model.RepoType
import uk.gov.hmrc.servicedependencies.model.DependencyScope
import uk.gov.hmrc.servicedependencies.model.BobbyVersionRange
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag

class ProductionBobbyNotificationServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar:

  "ProductionBobbyNotificationService.notifyBobbyErrorsInProduction" should:
    "notify teams when bobby violations exist" in new Setup:
      when(derivedBobbyReportRepository.find(SlugInfoFlag.Production))
        .thenReturn(Future.successful(bobbyReports))

      when(teamsAndReposConnector.cachedRepoMap()(using any[HeaderCarrier]))
        .thenReturn(Future.successful(teamToReposMap))

      when(slackNotificationsConnector.sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(SlackNotificationsConnector.Response(List.empty)))

      service.notifyBobbyErrorsInProduction().futureValue

      verify(slackNotificationsConnector, times(2)) // one message per team
        .sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier])


  case class Setup():
    given HeaderCarrier = HeaderCarrier()

    val violations = Seq(
      BobbyReport.Violation(
        depGroup    = "group",
        depArtefact = "artefact",
        depVersion  = Version("1.0.0"),
        depScopes   = Set(DependencyScope.Compile),
        range       = BobbyVersionRange("(,2.0.0)"),
        reason      = "test",
        from        = java.time.LocalDate.now().minus(1L, DAYS),
        exempt      = false
      )
    )

    val bobbyReports = Seq(
      BobbyReport(
        repoName     = "repo",
        repoVersion  = Version("0.1.0"),
        repoType     = RepoType.Service,
        violations   = violations,
        lastUpdated  = java.time.Instant.now(),
        latest       = false,
        production   = true,
        qa           = false,
        staging      = false,
        development  = false,
        externalTest = false,
        integration  = false
      ),
      BobbyReport(
        repoName     = "repo1",
        repoVersion  = Version("0.1.0"),
        repoType     = RepoType.Service,
        violations   = violations,
        lastUpdated  = java.time.Instant.now(),
        latest       = false,
        production   = true,
        qa           = false,
        staging      = false,
        development  = false,
        externalTest = false,
        integration  = false
      )
    )

    val teamToReposMap = Map(
      "repo"  -> (Seq("team1", "team2"), None),
      "repo1" -> (Seq("team1")         , None)
    )

    val derivedBobbyReportRepository = mock[DerivedBobbyReportRepository]
    val teamsAndReposConnector       = mock[TeamsAndRepositoriesConnector]
    val slackNotificationsConnector  = mock[SlackNotificationsConnector]

    val service = new ProductionBobbyNotificationService(derivedBobbyReportRepository, teamsAndReposConnector, slackNotificationsConnector)
