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

import cats.implicits._
import java.io.PrintStream
import java.time.LocalDate
import javax.inject.Inject
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{BobbyRulesSummaryRepo, SlugBlacklist, SlugInfoRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class DependencyLookupService @Inject() (
    serviceConfigs       : ServiceConfigsConnector
  , slugRepo             : SlugInfoRepository
  , bobbyRulesSummaryRepo: BobbyRulesSummaryRepo
  ) {

  import DependencyLookupService._

  def getLatestBobbyRuleViolations: Future[Option[BobbyRulesSummary]] =
    bobbyRulesSummaryRepo.getLatest

  def updateBobbyRulesSummary(implicit hc: HeaderCarrier): Future[Unit] = {
    def calculateCounts(rules: Seq[BobbyRule])(env: SlugInfoFlag): Future[Seq[((BobbyRule, SlugInfoFlag), Int)]] = {
      for {
        slugs      <- slugRepo.getSlugsForEnv(env)
        lookup     =  buildLookup(slugs)
        violations =  rules.map(rule => ((rule, env), findSlugsUsing(lookup, rule.organisation, rule.name, rule.range).length))
      } yield violations
    }

    for {
      rules   <- serviceConfigs.getBobbyRules.map(_.values.flatten.toSeq)
      counts  <- SlugInfoFlag.values.traverse(calculateCounts(rules))
      summary =  counts
                   .flatten
                   .toMap
      _       <- bobbyRulesSummaryRepo.add(BobbyRulesSummary(LocalDate.now, summary))
    } yield ()
  }

  def getHistoricBobbyRuleViolations: Future[HistoricBobbyRulesSummary] =
    bobbyRulesSummaryRepo.getHistoric
      .map(ss =>
      if (ss.isEmpty) HistoricBobbyRulesSummary(LocalDate.now, Map.empty)
      else ss
            .map(summary => HistoricBobbyRulesSummary.fromBobbyRulesSummary(summary))
            // TODO extrapolate if there are missing data values (array position is relative to date)
            .reduce((s1, s2) => s2.copy(summary = s2.summary combine s1.summary))
      )

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
      .filterNot(slug => SlugBlacklist.blacklistedSlugsSet.contains(slug.name))
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
