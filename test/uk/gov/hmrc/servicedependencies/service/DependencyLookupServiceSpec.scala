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

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{BobbyRulesSummaryRepository, SlugInfoRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedServiceDependenciesRepository

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}


class DependencyLookupServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with MongoSupport
     with IntegrationPatience {

  import DependencyLookupServiceTestData._

  import ExecutionContext.Implicits.global

  "findSlugsUsing" should {
    "return a list of slugs inside the version range" in {
      val slugLookup: Map[String, Map[Version, Set[String]]] = Map(
        "org.libs:mylib" -> Map(
            Version(1,0,0) -> Set("test1:1.99.3")
          , Version(1,3,0) -> Set("test2:2.0.1" )
          )
      )

      val res1 = DependencyLookupService.findSlugsUsing(
        lookup   = slugLookup,
        group    = "org.libs",
        artifact = "mylib",
        range    = BobbyVersionRange.parse("[1.1.0,]").get)

      res1.length shouldBe 1

      val res2 = DependencyLookupService.findSlugsUsing(
        lookup   = slugLookup,
        group    = "org.libs",
        artifact = "mylib",
        range    = BobbyVersionRange.parse("(1.0.0,]").get)

      res2.length shouldBe 1
    }
  }

  "getLatestBobbyRuleViolations" should {
      "return the number of slugs violating a bobby rule" in {
      val configService         = mock[ServiceConfigsConnector]
      val slugInfoRepository    = mock[SlugInfoRepository]

      val bobbyRulesSummaryRepo = new BobbyRulesSummaryRepository(mongoComponent) {
        import scala.jdk.FunctionConverters._

        private val store = new AtomicReference(List[BobbyRulesSummary]())

        override def add(summary: BobbyRulesSummary): Future[Unit] =
          Future.successful {
            store.updateAndGet(((l: List[BobbyRulesSummary]) => l ++ List(summary)).asJava)
          }

        override def getLatest(): Future[Option[BobbyRulesSummary]] =
          Future.successful(store.get.sortBy(_.date.toEpochDay).headOption)

        override def getHistoric(query: List[BobbyRuleQuery], from: LocalDate, to: LocalDate): Future[List[BobbyRulesSummary]] =
          Future.successful(store.get)

        override def clearAllData(): Future[Unit] = {
          store.set(List.empty)
          Future.unit
        }
      }

      val derivedServiceDependenciesRepository = mock[DerivedServiceDependenciesRepository]

      when(configService.getBobbyRules())
        .thenReturn(Future(BobbyRules(Map(("uk.gov.hmrc", "libs") -> List(bobbyRule)))))
      when(derivedServiceDependenciesRepository.findDependencies(any[SlugInfoFlag], any[Option[DependencyScope]]))
        .thenReturn(Future.successful(Seq.empty))
      when(derivedServiceDependenciesRepository.findDependencies(SlugInfoFlag.Production, Some(DependencyScope.Compile)))
        .thenReturn(Future.successful(Seq(serviceDep1, serviceDep11, serviceDep12)))

      val lookupService = new DependencyLookupService(configService, slugInfoRepository, bobbyRulesSummaryRepo, derivedServiceDependenciesRepository)

      lookupService.updateBobbyRulesSummary().futureValue
      val res = lookupService.getLatestBobbyRuleViolations.futureValue
      res.summary shouldBe Map(
          (bobbyRule, SlugInfoFlag.Latest      ) -> 0
        , (bobbyRule, SlugInfoFlag.Development ) -> 0
        , (bobbyRule, SlugInfoFlag.ExternalTest) -> 0
        , (bobbyRule, SlugInfoFlag.Production  ) -> 1
        , (bobbyRule, SlugInfoFlag.QA          ) -> 0
        , (bobbyRule, SlugInfoFlag.Staging     ) -> 0
        , (bobbyRule, SlugInfoFlag.Integration ) -> 0
        )
    }
  }

  "combineBobbyRulesSummaries" should {
    "handle empty summaries" in {
      DependencyLookupService.combineBobbyRulesSummaries(List.empty) shouldBe HistoricBobbyRulesSummary(LocalDate.now(), Map.empty)
    }

    "combine summaries" in {
      DependencyLookupService.combineBobbyRulesSummaries(List(
          bobbyRulesSummary(LocalDate.now()             , 1, 2)
        , bobbyRulesSummary(LocalDate.now().plusDays(-1), 3, 4)
        )) shouldBe historicBobbyRulesSummary(LocalDate.now().plusDays(-1), List(3, 1), List(4, 2))
    }

    "extrapolate missing values" in {
      DependencyLookupService.combineBobbyRulesSummaries(List(
          bobbyRulesSummary(LocalDate.now()             , 1, 2)
        , bobbyRulesSummary(LocalDate.now().plusDays(-2), 3, 4)
        )) shouldBe historicBobbyRulesSummary(LocalDate.now().plusDays(-2), List(3, 3, 1), List(4, 4, 2))
    }

    "drop values not in latest result" in {
      DependencyLookupService.combineBobbyRulesSummaries(List(
          bobbyRulesSummary(LocalDate.now()             , 1, 2, bobbyRule)
        , bobbyRulesSummary(LocalDate.now().plusDays(-2), 3, 4, bobbyRule.copy(name = "rule2"))
        )) shouldBe
          HistoricBobbyRulesSummary(LocalDate.now().plusDays(-2),
            Map( (bobbyRule, SlugInfoFlag.Latest    ) -> List(1, 1, 1)
              , (bobbyRule, SlugInfoFlag.Production) -> List(2, 2, 2)
              )
          )
    }
  }
}


object DependencyLookupServiceTestData {

  val dep1: SlugDependency = SlugDependency("", Version("5.11.0"), "org.libs", "mylib")
  val dep2: SlugDependency = SlugDependency("", Version("5.12.0"), "org.libs", "mylib")

  val serviceDep1 = ServiceDependency(
      slugName     = "test"
    , slugVersion  = Version("1.0.0")
    , teams        = List.empty
    , depGroup     = dep1.group
    , depArtefact  = dep1.artifact
    , depVersion   = dep1.version
    , scalaVersion = None
    , scopes       = Set(DependencyScope.Compile)
    )

  val serviceDep11 = serviceDep1.copy(slugVersion = Version("1.1.0"))

  val serviceDep12 = ServiceDependency(
      slugName     = "test"
    , slugVersion  = Version("1.2.0")
    , teams        = List.empty
    , depGroup     = dep2.group
    , depArtefact  = dep2.artifact
    , depVersion   = dep2.version
    , scalaVersion = None
    , scopes       = Set(DependencyScope.Compile)
    )

  val bobbyRule = BobbyRule(
    organisation = dep1.group,
    name         = dep1.artifact,
    range        = BobbyVersionRange.parse("(5.11.0,]").get,
    "testing",
    LocalDate.of(2000,1,1)
  )

  def bobbyRulesSummary(date: LocalDate, i: Int, j: Int, bobbyRule: BobbyRule = bobbyRule) =
    BobbyRulesSummary(date, Map(
        (bobbyRule, SlugInfoFlag.Latest    ) -> i
      , (bobbyRule, SlugInfoFlag.Production) -> j
      ))

  def historicBobbyRulesSummary(date: LocalDate, i: List[Int], j: List[Int], bobbyRule: BobbyRule = bobbyRule) =
    HistoricBobbyRulesSummary(date, Map(
        (bobbyRule, SlugInfoFlag.Latest    ) -> i
      , (bobbyRule, SlugInfoFlag.Production) -> j
      ))
}
