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

import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.Logging
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicedependencies.connector.VulnerabilitiesConnector
import uk.gov.hmrc.servicedependencies.connector.SlackNotificationsConnector
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag
import uk.gov.hmrc.http.HeaderCarrier

@Singleton
class ProductionVulnerabilitiesNotificationService @Inject()(
  vulnerabilitiesConnector   : VulnerabilitiesConnector,
  slackNotificationsConnector: SlackNotificationsConnector
)(using
  ec: ExecutionContext
) extends Logging:

  def notifyProductionVulnerabilities()(using hc: HeaderCarrier): Future[Unit] =
    for
      vulnerabilities <- vulnerabilitiesConnector.vulnerabilitySummaries(flag = Some(SlugInfoFlag.Production.asString))
      teamsToNotify   =  vulnerabilities
                           .flatMap(_.occurrences)
                           .flatMap(_.teams)
                           .distinct
      responses       <- teamsToNotify.foldLeftM(List.empty[(String, SlackNotificationsConnector.Response)]):
                           (acc, team) =>
                             slackNotificationsConnector
                               .sendMessage(errorNotification(team))
                               .map(resp => acc :+ (team, resp))
      _               =  responses.map:
                           case (team, rsp) if rsp.errors.nonEmpty => logger.warn(s"Sending Vulnerabilities in Production message to $team had errors ${rsp.errors.mkString(" : ")}")
                           case (team, _)                          => logger.info(s"Successfully sent Vulnerabilities in Production message to $team")
    yield ()

  private def errorNotification(team: String): SlackNotificationsConnector.Request =
    val heading = SlackNotificationsConnector.mrkdwnBlock(
      ":alarm: ACTION REQUIRED! :alarm:"
    )

    val msg = SlackNotificationsConnector.mrkdwnBlock(
      s"Hello $team, you have one or more service deployed in Production which has vulnerabilities marked as `Action Required`"
    )

    val link = SlackNotificationsConnector.mrkdwnBlock(
      s"See <https://catalogue.tax.service.gov.uk/vulnerabilities/services?team=$team&flag=production&curationStatus=ACTION_REQUIRED|Catalogue> for more information."
    )

    SlackNotificationsConnector.Request(
      channelLookup   = SlackNotificationsConnector.ChannelLookup.GithubTeam(team),
      displayName     = "MDTP Catalogue",
      emoji           = ":tudor-crown:",
      text            = "There are service(s) owned by you that are deployed in Production with vulnerabilities marked as action required!",
      blocks          = Seq(heading, msg, link),
      callbackChannel = Some("team-platops-alerts")
    )
