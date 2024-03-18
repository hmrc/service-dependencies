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
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.BobbyRulesSummaryRepository
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDependencyRepository, DerivedServiceDependenciesRepository}

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}


class DependencyLookupServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with MongoSupport
     with IntegrationPatience
    with BeforeAndAfterEach {

  import DependencyLookupServiceTestData._

  import ExecutionContext.Implicits.global

  private val configService                         = mock[ServiceConfigsConnector]
  private val derivedServiceDependenciesRepository  = mock[DerivedServiceDependenciesRepository]
  private val derivedDependenciesRepository         = mock[DerivedDependencyRepository]

  private val bobbyRulesSummaryRepo = new BobbyRulesSummaryRepository(mongoComponent) {

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
  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(configService)
    reset(derivedServiceDependenciesRepository)
    reset(derivedDependenciesRepository)
    bobbyRulesSummaryRepo.clearAllData().futureValue
  }

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

    "return the number of repositories violating a bobby rule for latest" in {

      when(configService.getBobbyRules())
        .thenReturn(Future(BobbyRules(Map(("uk.gov.hmrc", "libs") -> List(bobbyRule)))))
      when(derivedServiceDependenciesRepository.find(flag = any[SlugInfoFlag], group = any[Option[String]], artefact = any[Option[String]], scopes = any[Option[List[DependencyScope]]]))
        .thenReturn(Future.successful(Seq.empty))
      when(derivedDependenciesRepository.find(artefact = None, group = None, scopes = Some(DependencyScope.values)))
        .thenReturn(Future.successful(Seq(
          dep1
        , dep1.copy(repoVersion = Version("1.1.0"))
        , dep1.copy(repoVersion = Version("1.2.0"), depVersion = Version("5.12.0"))
        , dep2
      )))

      val lookupService = new DependencyLookupService(configService, bobbyRulesSummaryRepo, derivedServiceDependenciesRepository, derivedDependenciesRepository)

      lookupService.updateBobbyRulesSummary().futureValue
      val res = lookupService.getLatestBobbyRuleViolations.futureValue
      res.summary shouldBe Map(
        (bobbyRule, SlugInfoFlag.Latest) -> 2
        , (bobbyRule, SlugInfoFlag.Development) -> 0
        , (bobbyRule, SlugInfoFlag.ExternalTest) -> 0
        , (bobbyRule, SlugInfoFlag.Production) -> 0
        , (bobbyRule, SlugInfoFlag.QA) -> 0
        , (bobbyRule, SlugInfoFlag.Staging) -> 0
        , (bobbyRule, SlugInfoFlag.Integration) -> 0
      )
    }

    "return the number of repositories violating a bobby rule for environments" in {

      when(configService.getBobbyRules())
        .thenReturn(Future(BobbyRules(Map(("uk.gov.hmrc", "libs") -> List(bobbyRule)))))
      when(derivedServiceDependenciesRepository.find(flag = any[SlugInfoFlag], group = any[Option[String]], artefact = any[Option[String]], scopes = any[Option[List[DependencyScope]]]))
        .thenReturn(Future.successful(Seq.empty))
      when(derivedDependenciesRepository.find(group = any[Option[String]], artefact = any[Option[String]], repoType = any[Option[List[RepoType]]], scopes = any[Option[List[DependencyScope]]]))
        .thenReturn(Future.successful(Seq.empty))
      when(derivedServiceDependenciesRepository.find(SlugInfoFlag.Production, group = None, artefact = None, scopes = Some(List(DependencyScope.Compile))))
        .thenReturn(Future.successful(Seq(
          dep1
        , dep1.copy(repoVersion = Version("1.1.0"))
        , dep1.copy(repoVersion = Version("1.2.0"), depVersion = Version("5.12.0"))
        , dep2
        )))

      val lookupService = new DependencyLookupService(configService, bobbyRulesSummaryRepo, derivedServiceDependenciesRepository, derivedDependenciesRepository)

      lookupService.updateBobbyRulesSummary().futureValue
      val res = lookupService.getLatestBobbyRuleViolations.futureValue
      res.summary shouldBe Map(
          (bobbyRule, SlugInfoFlag.Latest      ) -> 0
        , (bobbyRule, SlugInfoFlag.Development ) -> 0
        , (bobbyRule, SlugInfoFlag.ExternalTest) -> 0
        , (bobbyRule, SlugInfoFlag.Production  ) -> 2
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
  val dep1 = MetaArtefactDependency(
    repoName     = "test-1"
  , repoVersion  = Version("1.0.0")
  , repoType     = RepoType.Service
  , teams        = List.empty
  , depGroup     = "org.libs"
  , depArtefact  = "mylib"
  , depVersion   = Version("5.11.0")
  // , scalaVersion = None
  , compileFlag   = false
  , providedFlag  = true
  , testFlag      = true
  , itFlag        = true
  , buildFlag     = true
  )

  val dep2 = MetaArtefactDependency(
    repoName     = "test-2"
  , repoVersion  = Version("1.0.0")
  , repoType     = RepoType.Service
  , teams        = List.empty
  , depGroup     = "org.libs"
  , depArtefact  = "mylib"
  , depVersion   = Version("5.12.0")
  // , scalaVersion = None
  , compileFlag   = false
  , providedFlag  = true
  , testFlag      = true
  , itFlag        = true
  , buildFlag     = true
  )

  val bobbyRule = BobbyRule(
    organisation = "org.libs",
    name         = "mylib",
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
