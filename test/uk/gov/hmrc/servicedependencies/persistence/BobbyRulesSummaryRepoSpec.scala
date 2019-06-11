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

package uk.gov.hmrc.servicedependencies.persistence

import cats.instances.all._
import cats.syntax.all._
import java.time.LocalDate
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{FailOnUnindexedQueries, MongoConnector, MongoSpecSupport, RepositoryPreparation}
import uk.gov.hmrc.servicedependencies.model.{BobbyRulesSummary, SlugDependency, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.connector.model.BobbyRule
import uk.gov.hmrc.servicedependencies.connector.model.BobbyVersionRange

class BobbyRulesSummaryRepoSpec
    extends WordSpecLike
       with Matchers
       with MongoSpecSupport
       with BeforeAndAfterEach
       with MockitoSugar
       with FailOnUnindexedQueries
       with RepositoryPreparation {

  override implicit val patienceConfig = {
    import org.scalatest.time.{Millis, Span, Seconds}
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(15, Millis))
  }

  val reactiveMongoComponent = new ReactiveMongoComponent {
    override val mongoConnector = {
      val mc = mock[MongoConnector]
      when(mc.db).thenReturn(mongo)
      mc
    }
  }

  val repo = new BobbyRulesSummaryRepoImpl(reactiveMongoComponent)

  override def beforeEach() {
    prepare(repo)
  }

  "SlugInfoRepository.add" should {
    val summary = bobbyRulesSummary(LocalDate.now, SlugInfoFlag.Development, 1)

    "insert correctly" in {
      repo.add(summary).futureValue
      repo.findAll().futureValue shouldBe Seq(summary)
    }

    "replace existing" in {
      repo.add(summary).futureValue
      repo.findAll().futureValue should have size 1

      val duplicate = summary.copy(summary = Map.empty)
      repo.add(duplicate).futureValue
      repo.findAll().futureValue shouldBe Seq(duplicate)
    }
  }

  "SlugInfoRepository.getLatest" should {

    "only search the latest slugs" in {
      val now = LocalDate.now

      val latest = bobbyRulesSummary(now             , SlugInfoFlag.Development, count = 1)
      val older  = bobbyRulesSummary(now.minusDays(1), SlugInfoFlag.Latest     , count = 2)
      val oldest = bobbyRulesSummary(now.minusDays(2), SlugInfoFlag.Production , count = 3)

      List(latest, older, oldest).traverse(repo.add).futureValue

      val result = repo.getLatest.futureValue shouldBe Some(latest)
    }
  }

  "SlugInfoRepository.getHistoric" should {
    "only search the latest slugs" in {
      val now = LocalDate.now

      val latest = bobbyRulesSummary(now             , SlugInfoFlag.Development, count = 1)
      val older  = bobbyRulesSummary(now.minusDays(1), SlugInfoFlag.Latest     , count = 2)
      val oldest = bobbyRulesSummary(now.minusDays(2), SlugInfoFlag.Production , count = 3)

      List(latest, older, oldest).traverse(repo.add).futureValue

      val result = repo.getHistoric.futureValue shouldBe Seq(latest, older, oldest)
    }
  }

  lazy val playFrontend = BobbyRule(
      organisation = "uk.gov.hmrc"
    , name         = "play-frontend"
    , range        = BobbyVersionRange("(,99.99.99)")
    , reason       = "Post Play Frontend upgrade"
    , from         = LocalDate.of(2015, 11, 2)
    )

  def bobbyRulesSummary(date: LocalDate, env: SlugInfoFlag, count: Int) =
    BobbyRulesSummary(date, Map(playFrontend -> Map(env -> List(count))))
}
