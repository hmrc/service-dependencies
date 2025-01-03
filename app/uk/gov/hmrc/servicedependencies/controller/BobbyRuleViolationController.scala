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

package uk.gov.hmrc.servicedependencies.controller

import play.api.libs.json.{Json, Writes}
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.model.{BobbyReport, BobbyRuleQuery, BobbyRulesSummary, HistoricBobbyRulesSummary, RepoType, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedBobbyReportRepository
import uk.gov.hmrc.servicedependencies.service.DependencyLookupService

import java.time.LocalDate
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class BobbyRuleViolationController @Inject() (
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  dependencyLookup             : DependencyLookupService,
  derivedBobbyReportRepository : DerivedBobbyReportRepository,
  cc                           : ControllerComponents
)(using
  ec: ExecutionContext
) extends BackendController(cc):

  def findBobbyRuleViolations: Action[AnyContent] =
    given Writes[BobbyRulesSummary] = BobbyRulesSummary.apiFormat
    Action.async:
      dependencyLookup
        .getLatestBobbyRuleViolations()
        .map(v => Ok(Json.toJson(v)))

  def findHistoricBobbyRuleViolations(query: List[BobbyRuleQuery], from: LocalDate, to: LocalDate): Action[AnyContent] =
    given Writes[HistoricBobbyRulesSummary] = HistoricBobbyRulesSummary.apiFormat
    Action.async:
      dependencyLookup
        .getHistoricBobbyRuleViolations(query, from, to)
        .map(v => Ok(Json.toJson(v)))

  def bobbyReports(team: Option[String], digitalService: Option[String], repoType: Option[RepoType],  flag: SlugInfoFlag): Action[AnyContent] =
    given Writes[BobbyReport] = BobbyReport.apiFormat
    Action.async: request =>
      given RequestHeader = request
      for
        oRepos  <- if   team.isDefined || digitalService.isDefined || repoType.isDefined
                   then teamsAndRepositoriesConnector
                          .getAllRepositories(teamName = team, digitalService = digitalService, repoType = repoType, archived = Some(false))
                          .map(xs => Some(xs.map(_.name)))
                   else Future.successful(None)
        results <- derivedBobbyReportRepository.find(flag, oRepos)
      yield Ok(Json.toJson(results))
