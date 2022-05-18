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

package uk.gov.hmrc.servicedependencies.persistence

import java.time.LocalDate
import cats.instances.all._
import cats.syntax.all._
import org.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicedependencies.model.{BobbyRule, BobbyRuleQuery, BobbyRulesSummary, BobbyVersionRange, SlugInfoFlag}

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.servicedependencies.model.SlugInfoFlag.Development

class BobbyRulesSummaryRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with MockitoSugar
    with DefaultPlayMongoRepositorySupport[BobbyRulesSummary] {

  override protected lazy val repository = new BobbyRulesSummaryRepository(mongoComponent)

  "BobbyRulesSummaryRepository.add" should {
    val summary = bobbyRulesSummary(playFrontend, LocalDate.now, SlugInfoFlag.Development, 1)

    "insert correctly" in {
      repository.add(summary).futureValue
      findAll().futureValue shouldBe Seq(summary)
    }

    "replace existing" in {
      repository.add(summary).futureValue
      findAll().futureValue should have size 1

      val duplicate = summary.copy(summary = Map.empty)
      repository.add(duplicate).futureValue
      findAll().futureValue shouldBe Seq(duplicate)
    }
  }

  "BobbyRulesSummaryRepository.getLatest" should {

    "only search the latest slugs" in {
      val now = LocalDate.now

      val latest = bobbyRulesSummary(playFrontend, now, SlugInfoFlag.Development, count              = 1)
      val older  = bobbyRulesSummary(playFrontend, now.minusDays(1), SlugInfoFlag.Latest, count     = 2)
      val oldest = bobbyRulesSummary(playFrontend, now.minusDays(2), SlugInfoFlag.Production, count = 3)

      List(latest, older, oldest).traverse(repository.add).futureValue

      repository.getLatest.futureValue shouldBe Some(latest)
    }
  }

  "BobbyRulesSummaryRepository.getHistoric" should {

    val query = List(
      BobbyRuleQuery("uk.gov.hmrc", "play-graphite", "(,3.6.2)"),
      BobbyRuleQuery("uk.gov.hmrc", "json-encryption", "(,3.2.0)")
    )

    "return bobbyRulesSummaries for queried rules only" in {
      val now = LocalDate.now

      val summaryNow = BobbyRulesSummary(now, Map((playFrontend, SlugInfoFlag.Development) -> 1,
        (playGraphite, SlugInfoFlag.Development) -> 1,
        (jsonEncryption, SlugInfoFlag.Development) -> 1))

      val summaryNowMinus1 = BobbyRulesSummary(now.minusDays(1), Map((playFrontend, SlugInfoFlag.Latest) -> 2,
        (playGraphite, SlugInfoFlag.Latest) -> 2,
        (jsonEncryption, SlugInfoFlag.Production) -> 2))

      val summaryNowMinus2 = BobbyRulesSummary(now.minusDays(2), Map((playFrontend, SlugInfoFlag.Production) -> 3,
        (playGraphite, SlugInfoFlag.Production) -> 4))

      val Data = List(summaryNow, summaryNowMinus1, summaryNowMinus2)
      repository.collection.insertMany(Data).toFuture().futureValue

      //Should not retrieve playFrontend rules
      val expectedResult = Seq(
        BobbyRulesSummary(now, Map(
          (playGraphite, SlugInfoFlag.Development) -> 1,
          (jsonEncryption, SlugInfoFlag.Development) -> 1)),
        BobbyRulesSummary(now.minusDays(1),  Map(
          (playGraphite, SlugInfoFlag.Latest) -> 2,
          (jsonEncryption, SlugInfoFlag.Production) -> 2)),
        BobbyRulesSummary(now.minusDays(2), Map(
          (playGraphite, SlugInfoFlag.Production) -> 4)))

      repository.getHistoric(query).futureValue shouldBe expectedResult
    }
  }

  lazy val playFrontend = BobbyRule(
    organisation = "uk.gov.hmrc",
    name         = "play-frontend",
    range        = BobbyVersionRange("(,99.99.99)"),
    reason       = "Post Play Frontend upgrade",
    from         = LocalDate.of(2015, 11, 2)
  )

  lazy val jsonEncryption = BobbyRule(
    organisation = "uk.gov.hmrc",
    name         = "json-encryption",
    range        = BobbyVersionRange("(,3.2.0)"),
    reason       = "Play 2.5 upgrade - security",
    from         = LocalDate.of(2015, 11, 2)
  )

  lazy val playGraphite = BobbyRule(
    organisation = "uk.gov.hmrc",
    name         = "play-graphite",
    range        = BobbyVersionRange("(,3.6.2)"),
    reason       = "Post Play Frontend upgrade",
    from         = LocalDate.of(2015, 11, 2)
  )

  def bobbyRulesSummary(rule: BobbyRule, date: LocalDate, env: SlugInfoFlag, count: Int) =
    BobbyRulesSummary(date, Map((rule, env) -> count))
}
