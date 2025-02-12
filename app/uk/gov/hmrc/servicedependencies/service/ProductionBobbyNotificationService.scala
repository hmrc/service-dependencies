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
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedBobbyReportRepository

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.model.BobbyReport
import uk.gov.hmrc.servicedependencies.connector.SlackNotificationsConnector

@Singleton
class ProductionBobbyNotificationService @Inject()(
  derivedBobbyReportRepository: DerivedBobbyReportRepository,
  teamsAndReposConnector      : TeamsAndRepositoriesConnector,
  slackNotificationsConnector : SlackNotificationsConnector
)(using
  ec: ExecutionContext
) extends Logging:

  def notifyBobbyErrorsInProduction()(using hc: HeaderCarrier): Future[Unit] =
    val now = java.time.LocalDate.now()
    for
      reports         <- derivedBobbyReportRepository.find(SlugInfoFlag.Production)
      teamToRepoMap   <- teamsAndReposConnector.cachedTeamToReposMap()
      filteredReports =  reports.filter(_.violations.exists(v => !v.exempt && now.isAfter(v.from)))
      groupedByTeam   =  teamToRepoMap.flatMap:
                           case (repoName, teams) =>
                             filteredReports.find(_.repoName == repoName).map(report => teams.map(_ -> report))
                         .flatten.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      responses       <- groupedByTeam.toList.foldLeftM(List.empty[(String, SlackNotificationsConnector.Response)]):
                           (acc, teamReports) =>
                             val (team, reports) = teamReports
                             slackNotificationsConnector
                               .sendMessage(errorNotification(team, reports.toSeq))
                               .map(resp => acc :+ (team, resp))
      _               =  responses.map:
                            case (team, rsp) if rsp.errors.nonEmpty => logger.warn(s"Sending Bobby Error message to $team had errors ${rsp.errors.mkString(" : ")}")
                            case (team, _)                          => logger.info(s"Successfully sent Bobby Error message to $team")
    yield ()

  private def errorNotification(team: String, reports: Seq[BobbyReport]): SlackNotificationsConnector.Request =
    val heading = SlackNotificationsConnector.mrkdwnBlock(
      ":alarm: ACTION REQUIRED! :platops-bobby:"
    )

    val msg = SlackNotificationsConnector.mrkdwnBlock(
      s"Hello $team, the following services are deployed in Production and are in violation of one or more Bobby Rule:"
    )

    val warnings = SlackNotificationsConnector.mrkdwnBlock(
      reports.map(r => s"`${r.repoName}`").distinct.mkString("\n")
    )

    val link = SlackNotificationsConnector.mrkdwnBlock(
      s"See <https://catalogue.tax.service.gov.uk/bobby-violations?teamName=$team&flag=production&isActive=true|Catalogue> for more information."
    )

    SlackNotificationsConnector.Request(
      channelLookup   = SlackNotificationsConnector.ChannelLookup.GithubTeam(team),
      displayName     = "MDTP Catalogue",
      emoji           = ":tudor-crown:",
      text            = "There are Bobby Rules being violated by your service(s) deployed in Production",
      blocks          = Seq(heading, msg, warnings, link),
      callbackChannel = Some("team-platops-alerts")
    )

