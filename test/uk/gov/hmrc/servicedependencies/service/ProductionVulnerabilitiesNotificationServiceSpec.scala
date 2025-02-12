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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.SlackNotificationsConnector
import uk.gov.hmrc.servicedependencies.connector.VulnerabilitiesConnector
import uk.gov.hmrc.servicedependencies.connector.DistinctVulnerability
import uk.gov.hmrc.servicedependencies.connector.VulnerabilityOccurrence
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ProductionVulnerabilitiesNotificationServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar:

  "ProductionVulnerabilitiesNotificationService.notifyProductionVulnerabilities" should:
    "notify teams when action required vulnerabilities are in production" in new Setup:
      when(vulnerabilitiesConnector.vulnerabilitySummaries(
        any(), any(), eqTo(Some(SlugInfoFlag.Production.asString))
      )(using any[HeaderCarrier]))
        .thenReturn(Future.successful(vulnerabilities))

      when(slackNotificationsConnector.sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(SlackNotificationsConnector.Response(List.empty)))

      service.notifyProductionVulnerabilities().futureValue

      verify(slackNotificationsConnector, times(2)) // one message per team
        .sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier])

  case class Setup():
    given HeaderCarrier = HeaderCarrier()

    val vulnerabilities = Seq(
      DistinctVulnerability(
        vulnerableComponentName = "component",
        vulnerableComponentVersion = "1.0.0",
        id = "CVE-7357",
        occurrences = Seq(
          VulnerabilityOccurrence(
            name    = "occ",
            version = "0.1.0",
            path    = "/test/path",
            teams   = Seq("team1"),
            service = "service"
          )
        )
      ),
      DistinctVulnerability(
        vulnerableComponentName = "test",
        vulnerableComponentVersion = "2.0.0",
        id = "CVE-1337",
        occurrences = Seq(
          VulnerabilityOccurrence(
            name    = "lib",
            version = "1.0.0",
            path    = "/a/b/c",
            teams   = Seq("team1", "team2"),
            service = "service1"
          )
        )
      )
    )

    val vulnerabilitiesConnector    = mock[VulnerabilitiesConnector]
    val slackNotificationsConnector = mock[SlackNotificationsConnector]

    val service = new ProductionVulnerabilitiesNotificationService(vulnerabilitiesConnector, slackNotificationsConnector)
      
