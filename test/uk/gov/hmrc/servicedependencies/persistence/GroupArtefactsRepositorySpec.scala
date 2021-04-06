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

package uk.gov.hmrc.servicedependencies.persistence

import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}
import uk.gov.hmrc.servicedependencies.model.GroupArtefacts
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos._
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedGroupArtefactRepository, DerivedMongoCollections}

class GroupArtefactsRepositorySpec
    extends AnyWordSpecLike
      with Matchers
      with OptionValues
      with MockitoSugar
      // We don't mixin IndexedMongoQueriesSupport here, as this repo makes use of queries not satisfied by an index
      with PlayMongoRepositorySupport[GroupArtefacts]
      with CleanMongoCollectionSupport {

  override lazy val repository = new DerivedGroupArtefactRepository(mongoComponent)

  lazy val slugInfoRepo = new SlugInfoRepository(mongoComponent)
  val dependencyGraphParser = new DependencyGraphParser
  val derivedCollectionGenerator = new DerivedMongoCollections(mongoComponent, dependencyGraphParser)

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)

  "GroupArtefactsRepository.findGroupsArtefacts" should {
    "return a map of artefact group to list of found artefacts" in {
      slugInfoRepo.add(slugInfo).futureValue
      derivedCollectionGenerator.generateArtefactLookup().futureValue

      val result = repository.findGroupsArtefacts.futureValue

      result should have size 1
      result.head.group           shouldBe "com.test.group"
      result.head.artefacts.toSet shouldBe Set("lib1", "lib2")
    }
  }
}
