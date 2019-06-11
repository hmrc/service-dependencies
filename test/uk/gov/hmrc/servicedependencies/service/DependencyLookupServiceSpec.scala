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

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any
import org.scalatest.{FlatSpec, FunSpec, Matchers}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.connector.model.{BobbyRule, BobbyVersion, BobbyVersionRange}
import uk.gov.hmrc.servicedependencies.model.{BobbyRulesSummary, ServiceDependency, SlugDependency, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.{BobbyRulesSummaryRepo, SlugInfoRepository}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration


class DependencyLookupServiceSpec
  extends FlatSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures {

  override implicit val patienceConfig = {
    import org.scalatest.time.{Millis, Span, Seconds}
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(15, Millis))
  }

  import DependencyLookupServiceTestData._
  import ExecutionContext.Implicits.global

  "slugToServiceDep" should "Convert a slug and dependency to a ServiceDependency" in {


    val res = DependencyLookupService.slugToServiceDep(slug1, dep1)

    res.depSemanticVersion shouldBe Some(Version(5,11,0))
    res.slugName           shouldBe "test"
    res.slugVersion        shouldBe "1.0.0"
  }


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
          Version(1,0,0) -> Set(ServiceDependency("test1", "1.99.3", List.empty, "org.libs", "mylib", "1.0.0"))
        , Version(1,3,0) -> Set(ServiceDependency("test2", "2.0.1" , List.empty, "org.libs", "mylib", "1.3.0")))
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

    val bobbyRulesSummaryRepo = new BobbyRulesSummaryRepo {
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
    }

    implicit val hc = HeaderCarrier()

    when(configService.getBobbyRules()).thenReturn(Future(Map("libs" -> List(bobbyRule))))
    when(slugInfoRepository.getSlugsForEnv(any[SlugInfoFlag])).thenReturn(Future(Seq.empty))
    when(slugInfoRepository.getSlugsForEnv(SlugInfoFlag.Production)).thenReturn(Future(Seq(slug1, slug11, slug12)))

    val lookupService = new DependencyLookupService(configService, slugInfoRepository, bobbyRulesSummaryRepo)

    lookupService.updateBobbyRulesSummary.futureValue
    val optRes = lookupService.getLatestBobbyRuleViolations.futureValue
    optRes.isDefined shouldBe true
    val Some(res) = optRes
    res.summary.get(bobbyRule) shouldBe(Some(Map(
        SlugInfoFlag.Latest       -> List(0)
      , SlugInfoFlag.Development  -> List(0)
      , SlugInfoFlag.ExternalTest -> List(0)
      , SlugInfoFlag.Production   -> List(1)
      , SlugInfoFlag.QA           -> List(0)
      , SlugInfoFlag.Staging      -> List(0)
      )))
  }
}


object DependencyLookupServiceTestData {

  val dep1: SlugDependency = SlugDependency("", "5.11.0", "org.libs", "mylib")
  val dep2: SlugDependency = SlugDependency("", "5.12.0", "org.libs", "mylib")

  val slug1 = SlugInfo(
      uri = "http://slugs.com/test/test-1.0.0.tgz"
    , name          = "test"
    , version       = Version("1.0.0")
    , teams         = List.empty
    , runnerVersion = "0.5.2"
    , classpath     = "classpath="
    , jdkVersion    = "1.8.0"
    , dependencies  = List(dep1)
    , applicationConfig = "config"
    , ""
    , latest       = true
    , production   = false
    , qa           = false
    , staging      = false
    , development  = false
    , externalTest = false
    )

  val slug11 = slug1.copy(version = Version(1,1,0), uri = "http://slugs.com/test/test-1.1.0.tgz")

  val slug12 = slug1.copy(version = Version(1,2,0), uri = "http://slugs.com/test/test-1.2.0.tgz", dependencies = List(dep2))

  val bobbyRule = BobbyRule(organisation = dep1.group, name = dep1.artifact, range = BobbyVersionRange.parse("(5.11.0,]").get, "testing", LocalDate.of(2000,1,1))
}