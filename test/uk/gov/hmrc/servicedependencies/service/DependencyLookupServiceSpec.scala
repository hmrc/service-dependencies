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

import java.time.{LocalDate, LocalDateTime}
import java.util.concurrent.atomic.AtomicReference

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{BobbyRulesSummaryRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class DependencyLookupServiceSpec
  extends AnyFlatSpec
     with Matchers
     with MockitoSugar
     with MongoSupport {

  override implicit val patienceConfig = PatienceConfig(timeout = 2.seconds, interval = 15.millis)

  import DependencyLookupServiceTestData._

  import ExecutionContext.Implicits.global

  "BuildLookup" should "Build a map of dependencies showing which slug uses which version" in {
    val res = DependencyLookupService.buildLookup(Seq(slug11, slug1, slug12))

    res("org.libs:mylib").keySet.contains(Version(5, 11, 0))
    res("org.libs:mylib").keySet.contains(Version(5, 12, 0))
    res("org.libs:mylib")(Version(5, 11, 0)).size shouldBe 2
    res("org.libs:mylib")(Version(5, 11, 0)).exists(_.slugVersion == "1.0.0") shouldBe true
    res("org.libs:mylib")(Version(5, 11, 0)).exists(_.slugVersion == "1.1.0") shouldBe true
    res("org.libs:mylib")(Version(5, 12, 0)).size shouldBe 1
    res("org.libs:mylib")(Version(5, 12, 0)).exists(_.slugVersion == "1.2.0") shouldBe true
  }


  "findSlugsUsing" should "return a list of slugs inside the version range" in {

    val slugLookup: Map[String, Map[Version, Set[ServiceDependency]]] = Map(
      "org.libs:mylib" -> Map(
          Version(1,0,0) -> Set(ServiceDependency("test1", "1.99.3", List.empty, "org.libs", "mylib", "1.0.0", scalaVersion = None, scopes = Set.empty))
        , Version(1,3,0) -> Set(ServiceDependency("test2", "2.0.1" , List.empty, "org.libs", "mylib", "1.3.0", scalaVersion = None, scopes = Set.empty)))
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


  "getLatestBobbyRuleViolations" should "return the number of slugs violating a bobby rule" in {
    val configService         = mock[ServiceConfigsConnector]
    val slugInfoRepository    = mock[SlugInfoRepository]

    val bobbyRulesSummaryRepo = new BobbyRulesSummaryRepository(mongoComponent) {
      import scala.compat.java8.FunctionConverters._

      private val store = new AtomicReference(List[BobbyRulesSummary]())

      override def add(summary: BobbyRulesSummary): Future[Unit] =
        Future {
          store.updateAndGet(((l: List[BobbyRulesSummary]) => l ++ List(summary)).asJava)
        }

      override def getLatest: Future[Option[BobbyRulesSummary]] =
        Future(store.get.sortBy(_.date.toEpochDay).headOption)

      override def getHistoric: Future[List[BobbyRulesSummary]] =
        Future(store.get)

      override def clearAllData: Future[Boolean] = {
        store.set(List.empty)
        Future(true)
      }
    }

    when(configService.getBobbyRules).thenReturn(Future(BobbyRules(Map(("uk.gov.hmrc", "libs") -> List(bobbyRule)))))
    when(slugInfoRepository.getSlugsForEnv(any[SlugInfoFlag])).thenReturn(Future(Seq.empty))
    when(slugInfoRepository.getSlugsForEnv(SlugInfoFlag.Production)).thenReturn(Future(Seq(slug1, slug11, slug12)))

    val lookupService = new DependencyLookupService(configService, slugInfoRepository, bobbyRulesSummaryRepo)

    lookupService.updateBobbyRulesSummary.futureValue
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

  "combineBobbyRulesSummaries" should "handle empty summaries" in {
    DependencyLookupService.combineBobbyRulesSummaries(List.empty) shouldBe HistoricBobbyRulesSummary(LocalDate.now, Map.empty)
  }

  it should "combine summaries" in {
    DependencyLookupService.combineBobbyRulesSummaries(List(
        bobbyRulesSummary(LocalDate.now             , 1, 2)
      , bobbyRulesSummary(LocalDate.now.plusDays(-1), 3, 4)
      )) shouldBe historicBobbyRulesSummary(LocalDate.now.plusDays(-1), List(3, 1), List(4, 2))
  }

  it should "extrapolate missing values" in {
    DependencyLookupService.combineBobbyRulesSummaries(List(
        bobbyRulesSummary(LocalDate.now             , 1, 2)
      , bobbyRulesSummary(LocalDate.now.plusDays(-2), 3, 4)
      )) shouldBe historicBobbyRulesSummary(LocalDate.now.plusDays(-2), List(3, 3, 1), List(4, 4, 2))
  }

 it should "drop values not in latest result" in {
    DependencyLookupService.combineBobbyRulesSummaries(List(
        bobbyRulesSummary(LocalDate.now             , 1, 2, bobbyRule)
      , bobbyRulesSummary(LocalDate.now.plusDays(-2), 3, 4, bobbyRule.copy(name = "rule2"))
      )) shouldBe
        HistoricBobbyRulesSummary(LocalDate.now.plusDays(-2),
          Map( (bobbyRule, SlugInfoFlag.Latest    ) -> List(1, 1, 1)
             , (bobbyRule, SlugInfoFlag.Production) -> List(2, 2, 2)
             )
        )
  }
}


object DependencyLookupServiceTestData {

  val dep1: SlugDependency = SlugDependency("", "5.11.0", "org.libs", "mylib")
  val dep2: SlugDependency = SlugDependency("", "5.12.0", "org.libs", "mylib")

  val slug1 = SlugInfo(
      uri           = "http://slugs.com/test/test-1.0.0.tgz"
    , created       = LocalDateTime.of(2019, 6, 28, 11, 51,23)
    , name          = "test"
    , version       = Version("1.0.0")
    , teams         = List.empty
    , runnerVersion = "0.5.2"
    , classpath     = "classpath="
    , java          = JavaInfo("1.8.0", "Oracle", "JDK")
    , dependencies  = List(dep1)
    , dependencyDotCompile = ""
    , dependencyDotTest    = ""
    , dependencyDotBuild   = ""
    , applicationConfig = "config"
    , ""
    , latest       = true
    , production   = false
    , qa           = false
    , staging      = false
    , development  = false
    , externalTest = false
    , integration  = false
    )

  val slug11 = slug1.copy(version = Version(1,1,0), uri = "http://slugs.com/test/test-1.1.0.tgz")

  val slug12 = slug1.copy(version = Version(1,2,0), uri = "http://slugs.com/test/test-1.2.0.tgz", dependencies = List(dep2))

  val bobbyRule = BobbyRule(organisation = dep1.group, name = dep1.artifact, range = BobbyVersionRange.parse("(5.11.0,]").get, "testing", LocalDate.of(2000,1,1))

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
