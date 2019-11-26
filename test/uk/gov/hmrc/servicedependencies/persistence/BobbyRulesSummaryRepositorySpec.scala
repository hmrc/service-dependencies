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

import java.time.LocalDate

import cats.instances.all._
import cats.syntax.all._
import org.mockito.MockitoSugar
import org.mongodb.scala.ReadPreference
import org.mongodb.scala.model.IndexModel
import org.scalatest.{Matchers, WordSpecLike}
import play.api.Configuration
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport
import uk.gov.hmrc.mongo.throttle.ThrottleConfig
import uk.gov.hmrc.servicedependencies.model.{BobbyRule, BobbyRulesSummary, BobbyVersionRange, SlugInfoFlag}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class BobbyRulesSummaryRepositorySpec
    extends WordSpecLike
    with Matchers
    with MockitoSugar
    with DefaultMongoCollectionSupport {

  val throttleConfig = new ThrottleConfig(Configuration())

  private val repo = new BobbyRulesSummaryRepository(mongoComponent, throttleConfig) {
    def findAll(): Future[Seq[BobbyRulesSummary]] =
      collection.withReadPreference(ReadPreference.secondaryPreferred).find().toThrottledFuture.map(_.toList)
  }

  override protected val collectionName: String   = repo.collectionName
  override protected val indexes: Seq[IndexModel] = repo.indexes

  override implicit val patienceConfig: PatienceConfig = {
    import org.scalatest.time.{Millis, Seconds, Span}
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(15, Millis))
  }

  "BobbyRulesSummaryRepository.add" should {
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

  "BobbyRulesSummaryRepository.getLatest" should {

    "only search the latest slugs" in {
      val now = LocalDate.now

      val latest = bobbyRulesSummary(now, SlugInfoFlag.Development, count             = 1)
      val older  = bobbyRulesSummary(now.minusDays(1), SlugInfoFlag.Latest, count     = 2)
      val oldest = bobbyRulesSummary(now.minusDays(2), SlugInfoFlag.Production, count = 3)

      List(latest, older, oldest).traverse(repo.add).futureValue

      repo.getLatest.futureValue shouldBe Some(latest)
    }
  }

  "BobbyRulesSummaryRepository.getHistoric" should {
    "only search the latest slugs" in {
      val now = LocalDate.now

      val latest = bobbyRulesSummary(now, SlugInfoFlag.Development, count             = 1)
      val older  = bobbyRulesSummary(now.minusDays(1), SlugInfoFlag.Latest, count     = 2)
      val oldest = bobbyRulesSummary(now.minusDays(2), SlugInfoFlag.Production, count = 3)

      List(latest, older, oldest).traverse(repo.add).futureValue

      repo.getHistoric.futureValue shouldBe Seq(latest, older, oldest)
    }
  }

  lazy val playFrontend = BobbyRule(
    organisation = "uk.gov.hmrc",
    name         = "play-frontend",
    range        = BobbyVersionRange("(,99.99.99)"),
    reason       = "Post Play Frontend upgrade",
    from         = LocalDate.of(2015, 11, 2)
  )

  def bobbyRulesSummary(date: LocalDate, env: SlugInfoFlag, count: Int) =
    BobbyRulesSummary(date, Map((playFrontend, env) -> count))

}
