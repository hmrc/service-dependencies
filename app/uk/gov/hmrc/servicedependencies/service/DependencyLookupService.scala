/*
 * Copyright 2022 HM Revenue & Customs
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

import java.time.LocalDate
import java.time.temporal.ChronoUnit

import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{BobbyRulesSummaryRepository, SlugInfoRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedServiceDependenciesRepository

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class DependencyLookupService @Inject() (
  serviceConfigs       : ServiceConfigsConnector
, slugRepo             : SlugInfoRepository
, bobbyRulesSummaryRepo: BobbyRulesSummaryRepository
, derivedServiceDependenciesRepository: DerivedServiceDependenciesRepository
)(implicit ec: ExecutionContext
) extends Logging {

  import DependencyLookupService._

  def getLatestBobbyRuleViolations: Future[BobbyRulesSummary] =
    bobbyRulesSummaryRepo.getLatest
      .map(_.getOrElse(BobbyRulesSummary(LocalDate.now, Map.empty)))

  def updateBobbyRulesSummary(): Future[Unit] = {
    def calculateCounts(rules: Seq[BobbyRule])(env: SlugInfoFlag): Future[Seq[((BobbyRule, SlugInfoFlag), Int)]] = {
      logger.debug(s"calculateCounts($env)")
      for {
        lookup <- derivedServiceDependenciesRepository.findDependencies(env, Some(DependencyScope.Compile)) // we could look for violations in all scopes (but number will not align to results when drilling down in catalogue)
          .map(_
            .groupBy(d => s"${d.depGroup}:${d.depArtefact}")
            .view
            .mapValues(
              _.groupBy(_.depVersion)
                .view
                .mapValues(_.map(d => s"${d.slugName}:${d.slugVersion}").toSet)
                .toMap
            )
            .toMap
          )
        violations = rules.map(rule => ((rule, env), findSlugsUsing(lookup, rule.organisation, rule.name, rule.range).length))
      } yield violations
    }

    for {
      rules <- serviceConfigs.getBobbyRules.map(_.asMap.values.flatten.toSeq)
      _ = logger.debug(s"Found ${rules.size} rules")
      // traverse (in parallel) uses more memory and adds contention on data source - fold through it instead
      counts <- SlugInfoFlag.values.foldLeftM(Seq[((BobbyRule, SlugInfoFlag), Int)]())((acc, env) =>
        calculateCounts(rules)(env).map(acc ++ _)
      )
      summary = counts.toMap
      _ <- bobbyRulesSummaryRepo.add(BobbyRulesSummary(LocalDate.now, summary))
    } yield ()
  }

  def getHistoricBobbyRuleViolations(query: List[BobbyRuleQuery], from: LocalDate, to: LocalDate): Future[HistoricBobbyRulesSummary] =
    bobbyRulesSummaryRepo.getHistoric(query, from, to)
      .map(combineBobbyRulesSummaries)
}


object DependencyLookupService {

  def findSlugsUsing(
      lookup  : Map[String, Map[Version, Set[String]]]
    , group   : String
    , artifact: String
    , range   : BobbyVersionRange
    ): Seq[String] =
      lookup
        .getOrElse(s"$group:$artifact", Map.empty)
        .view
        .filterKeys(v => range.includes(v))
        .toMap
        .values
        .flatten
        .toSeq

  def combineBobbyRulesSummaries(l: List[BobbyRulesSummary]): HistoricBobbyRulesSummary =
    l match {
      case Nil          => HistoricBobbyRulesSummary(LocalDate.now, Map.empty)
      case head :: rest => rest
                             .foldLeft(HistoricBobbyRulesSummary.fromBobbyRulesSummary(head)){ (acc, s) =>
                               val daysBetween = ChronoUnit.DAYS.between(s.date, acc.date).toInt
                               val res = acc.summary.foldLeft(acc.summary) { case (acc2, (k, v)) =>
                                  acc2 + (k -> (List.fill(daysBetween)(s.summary.getOrElse(k, v.head)) ++ v))
                               }
                               HistoricBobbyRulesSummary(date = s.date, summary = res)
                             }
    }

}
