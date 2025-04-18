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

import cats.implicits._
import play.api.Logging
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.BobbyRulesSummaryRepository
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDeployedDependencyRepository, DerivedLatestDependencyRepository}

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependencyLookupService @Inject() (
  serviceConfigs                     : ServiceConfigsConnector
, bobbyRulesSummaryRepo              : BobbyRulesSummaryRepository
, derivedDeployedDependencyRepository: DerivedDeployedDependencyRepository
, derivedLatestDependencyRepository  : DerivedLatestDependencyRepository
)(using ec: ExecutionContext
) extends Logging:

  import DependencyLookupService._

  def getLatestBobbyRuleViolations(): Future[BobbyRulesSummary] =
    bobbyRulesSummaryRepo
      .getLatest()
      .map(_.getOrElse(BobbyRulesSummary(LocalDate.now(), Map.empty)))

  def updateBobbyRulesSummary(): Future[Unit] = {
    def calculateCounts(rules: Seq[BobbyRule])(slugInfoFlag: SlugInfoFlag): Future[Seq[((BobbyRule, SlugInfoFlag), Int)]] =
      for
        dependencies <- slugInfoFlag match
                          case SlugInfoFlag.Latest => derivedLatestDependencyRepository.find(scopes = Some(DependencyScope.values.toSeq))
                          case _                   => derivedDeployedDependencyRepository.findWithDeploymentLookup(scopes = Some(List(DependencyScope.Compile)), flag = slugInfoFlag)
        lookup       =  dependencies
                          .groupBy(d => s"${d.depGroup}:${d.depArtefact}")
                          .view
                          .mapValues:
                            _.groupBy(_.depVersion)
                              .view
                              .mapValues(_.map(d => s"${d.repoName}:${d.repoVersion}").toSet)
                              .toMap
                          .toMap
        violations    = rules.map: rule =>
                          ((rule, slugInfoFlag), findSlugsUsing(lookup, rule.organisation, rule.name, rule.range).length)
      yield violations

    for
      rules   <- serviceConfigs.getBobbyRules().map(_.asMap.values.flatten.toSeq)
      _       =  logger.debug(s"Found ${rules.size} rules")
      counts  <- SlugInfoFlag
                   .values.toList  // traverse (in parallel) uses more memory and adds contention on data source - fold through it instead
                   .foldLeftM(Seq[((BobbyRule, SlugInfoFlag), Int)]()): (acc, slugInfoFlag) =>
                     calculateCounts(rules)(slugInfoFlag).map(acc ++ _)
      _       <- bobbyRulesSummaryRepo.add(BobbyRulesSummary(LocalDate.now(), counts.toMap))
    yield ()
  }

  def getHistoricBobbyRuleViolations(query: List[BobbyRuleQuery], from: LocalDate, to: LocalDate): Future[HistoricBobbyRulesSummary] =
    bobbyRulesSummaryRepo.getHistoric(query, from, to)
      .map(combineBobbyRulesSummaries)

object DependencyLookupService:


  def findSlugsUsing(
      lookup  : Map[String, Map[Version, Set[String]]]
    , group   : String
    , artifact: String
    , range   : BobbyVersionRange
    ): Seq[String] =
      lookup
        .getOrElse(s"$group:$artifact", Map.empty)
        .view
        .filterKeys(range.includes)
        .toMap
        .values
        .flatten
        .toSeq

  def combineBobbyRulesSummaries(l: List[BobbyRulesSummary]): HistoricBobbyRulesSummary =
    l match
      case Nil          => HistoricBobbyRulesSummary(LocalDate.now(), Map.empty)
      case head :: rest => rest
                             .foldLeft(HistoricBobbyRulesSummary.fromBobbyRulesSummary(head)): (acc, s) =>
                               val daysBetween = ChronoUnit.DAYS.between(s.date, acc.date).toInt
                               val res = acc.summary.foldLeft(acc.summary):
                                  case (acc2, (k, v)) =>
                                    acc2 + (k -> (List.fill(daysBetween)(s.summary.getOrElse(k, v.head)) ++ v))
                               HistoricBobbyRulesSummary(date = s.date, summary = res)
