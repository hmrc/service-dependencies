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

import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatest.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{FailOnUnindexedQueries, MongoConnector, MongoSpecSupport, RepositoryPreparation}
import uk.gov.hmrc.servicedependencies.model.DependencyConfig

class DependencyConfigRepositorySpec
    extends WordSpecLike
       with Matchers
       with MongoSpecSupport
       with BeforeAndAfterEach
       with MockitoSugar
       with FailOnUnindexedQueries
       with RepositoryPreparation {

  val reactiveMongoComponent = new ReactiveMongoComponent {
    override val mongoConnector = {
      val mc = mock[MongoConnector]
      when(mc.db).thenReturn(mongo)
      mc
    }
  }

  val dependencyConfigRepository = new DependencyConfigRepository(reactiveMongoComponent)

  override def beforeEach() {
    prepare(dependencyConfigRepository)
  }

  "DependencyConfigRepository.add" should {
    "insert correctly" in {
      await(dependencyConfigRepository.add(dependencyConfig))
      await(dependencyConfigRepository.getAllEntries) shouldBe Seq(dependencyConfig)
    }

    "replace existing" in {
      await(dependencyConfigRepository.add(dependencyConfig)) shouldBe true
      await(dependencyConfigRepository.getAllEntries) should have size 1

      val duplicate = dependencyConfig.copy(configs = Map.empty)
      await(dependencyConfigRepository.add(duplicate)) shouldBe true
      await(dependencyConfigRepository.getAllEntries) shouldBe Seq(duplicate)
    }
  }

  "DependencyConfigRepository.clearAllData" should {
    "delete everything" in {
      await(dependencyConfigRepository.add(dependencyConfig))
      await(dependencyConfigRepository.getAllEntries) should have size 1

      await(dependencyConfigRepository.clearAllData)
      await(dependencyConfigRepository.getAllEntries) shouldBe Nil
    }
  }


  "DependencyConfigRepository.getDependencyConfig" should {

    "return match" in {
      await(dependencyConfigRepository.add(dependencyConfig))

      val result = await(dependencyConfigRepository.getDependencyConfig(dependencyConfig.group, dependencyConfig.artefact, dependencyConfig.version))

      result.isDefined shouldBe true

      result.get shouldBe dependencyConfig
    }

    "return none if no match" in {
      await(dependencyConfigRepository.add(dependencyConfig))

      val result = await(dependencyConfigRepository.getDependencyConfig(dependencyConfig.group + "1", dependencyConfig.artefact, dependencyConfig.version))

      result.isDefined shouldBe false
    }
  }

  val dependencyConfig =
    DependencyConfig(
            group    = "uk.gov.hmrc"
          , artefact = "time"
          , version  = "3.2.0"
          , configs  = Map(
                "includes.conf"  -> "a = 1"
              , "reference.conf" -> """|include "includes.conf"
                                       |
                                       |b = 2""".stripMargin
              )
          )
}
