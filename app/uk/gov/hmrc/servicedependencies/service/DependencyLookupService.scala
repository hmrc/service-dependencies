/*
 * Copyright 2021 HM Revenue & Customs
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

import java.io.PrintStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit

import cats.implicits._
import javax.inject.Inject
import play.api.Logging
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{BobbyRulesSummaryRepository, SlugDenylist, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}


class DependencyLookupService @Inject() (
  serviceConfigs       : ServiceConfigsConnector
, slugRepo             : SlugInfoRepository
, bobbyRulesSummaryRepo: BobbyRulesSummaryRepository
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
        slugs      <- slugRepo.getSlugsForEnv(env)
        _          =  logger.debug(s"Found ${slugs.size} slugs for $env")
        lookup     =  buildLookup(slugs)
        violations =  rules.map(rule => ((rule, env), findSlugsUsing(lookup, rule.organisation, rule.name, rule.range).length))
      } yield violations
    }

    for {
      rules   <- serviceConfigs.getBobbyRules.map(_.asMap.values.flatten.toSeq)
      _       =  logger.debug(s"Found ${rules.size} rules")
                 // traverse (in parallel) uses more memory and adds contention on data source - fold through it instead
      counts  <- SlugInfoFlag.values.foldLeftM(Seq[((BobbyRule, SlugInfoFlag), Int)]()){ case (acc, env) =>
                   calculateCounts(rules)(env).map(acc ++ _)
                 }
      summary =  counts
                   .toMap
      _       <- bobbyRulesSummaryRepo.add(BobbyRulesSummary(LocalDate.now, summary))
    } yield ()
  }

  def getHistoricBobbyRuleViolations: Future[HistoricBobbyRulesSummary] =
    bobbyRulesSummaryRepo.getHistoric
      .map(combineBobbyRulesSummaries)

  /**
    * A alternative version of SlugInfoService's findServicesWithDependency.
    * Likely faster, especially if the lookup table is cached.
    */
  def findServicesWithDependency(
      env         : SlugInfoFlag
    , group       : String
    , artefact    : String
    , versionRange: BobbyVersionRange
    ): Future[Seq[ServiceDependency]] =
      for {
        slugs  <- slugRepo.getSlugsForEnv(env)
        lookup =  buildLookup(slugs)
        result =  findSlugsUsing(lookup, group, artefact, versionRange)
      } yield result
}


object DependencyLookupService {

  def buildLookup(slugs: Seq[SlugInfo]): Map[String, Map[Version, Set[ServiceDependency]]] =
    slugs
      .filterNot(slug => SlugDenylist.denylistedSlugsSet.contains(slug.name))
      .flatMap(slug => slug.dependencies.map(deps => (slug, deps)))
      .groupBy { case (_, dep) => s"${dep.group}:${dep.artifact}" }
      .mapValues(
        _.groupBy { case (_, dep)  => Version(dep.version) }
         .mapValues(_.map { case (slug, dep) => slugToServiceDep(slug, dep) }.toSet)
      )


  def slugToServiceDep(slug: SlugInfo, dep: SlugDependency): ServiceDependency =
    ServiceDependency(
      slugName    = slug.name,
      slugVersion = slug.version.toString,
      teams       = List.empty,
      depGroup    = dep.group,
      depArtefact = dep.artifact,
      depVersion  = dep.version.toString)


  def findSlugsUsing(
      lookup  : Map[String, Map[Version, Set[ServiceDependency]]]
    , group   : String
    , artifact: String
    , range   : BobbyVersionRange
    ): Seq[ServiceDependency] =
      lookup
        .getOrElse(s"$group:$artifact", Map.empty)
        .filterKeys(v => range.includes(v))
        .values
        .flatten
        .toSeq

  def combineBobbyRulesSummaries(l: List[BobbyRulesSummary]): HistoricBobbyRulesSummary =
    l match {
      case Nil          => HistoricBobbyRulesSummary(LocalDate.now, Map.empty)
      case head :: rest => rest
                             .foldLeft(HistoricBobbyRulesSummary.fromBobbyRulesSummary(head)){ case (acc, s) =>
                               val daysBetween = ChronoUnit.DAYS.between(s.date, acc.date).toInt
                               val res = acc.summary.foldLeft(acc.summary) { case (acc2, (k, v)) =>
                                  acc2 + (k -> (List.fill(daysBetween)(s.summary.getOrElse(k, v.head)) ++ v))
                               }
                               HistoricBobbyRulesSummary(date = s.date, summary = res)
                             }
    }

  def printTree(
      t  : Map[String, Map[Version, Set[String]]]
    , out: PrintStream                            = System.out
    ): Unit = {
      val res = t.map {
        case (k, v) =>
          List(k + ":") ++
            v.flatMap {
              case (ver, slugs) =>
                List(" |_" + ver.toString + " - (" + slugs.size + ")") ++
                  slugs.map("  |_" + _.toLowerCase())
            }
      }
      println(res.mkString("\n"))
    }
}
