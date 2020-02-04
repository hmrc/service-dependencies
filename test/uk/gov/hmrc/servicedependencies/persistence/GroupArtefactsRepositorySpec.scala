/*
 * Copyright 2020 HM Revenue & Customs
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
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.IndexModel
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}
import org.scalatest.OptionValues
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.servicedependencies.model.GroupArtefacts
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class GroupArtefactsRepositorySpec
    extends AnyWordSpecLike
      with Matchers
      with OptionValues
      with MockitoSugar
      // We don't mixin IndexedMongoQueriesSupport here, as this repo makes use of queries not satisfied by an index
      with CleanMongoCollectionSupport {

  lazy val groupArtefactsRepo = new GroupArtefactRepository(mongoComponent, throttleConfig)
  lazy val slugInfoRepo = new SlugInfoRepository(mongoComponent, throttleConfig)

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)

  override protected lazy val collectionName: String          = groupArtefactsRepo.collectionName
  override protected lazy val indexes: Seq[IndexModel]        = groupArtefactsRepo.indexes
  override protected lazy val optSchema: Option[BsonDocument] = groupArtefactsRepo.optSchema

  import GroupArtefactsRepositorySpec._

  "GroupArtefactsRepository.findGroupsArtefacts" should {
    "return a map of artefact group to list of found artefacts" in {
      slugInfoRepo.add(slugInfo).futureValue

      val result = groupArtefactsRepo.findGroupsArtefacts.futureValue

      result should have size 1
      result.headOption.value should have (
        group ("com.test.group"),
        artefacts (Set("lib1", "lib2"))
      )
    }
  }

}

private object GroupArtefactsRepositorySpec {
  def group(expectedGroup: String): HavePropertyMatcher[GroupArtefacts, String] =
    new HavePropertyMatcher[GroupArtefacts, String] {
      def apply(groupArtefacts: GroupArtefacts): HavePropertyMatchResult[String] = {
        val actualGroup = groupArtefacts.group
        HavePropertyMatchResult(
          expectedGroup == actualGroup,
          "group",
          expectedGroup,
          actualGroup
        )
      }
    }

  def artefacts(expectedArtefacts: Set[String]): HavePropertyMatcher[GroupArtefacts, Set[String]] =
    new HavePropertyMatcher[GroupArtefacts, Set[String]] {
      def apply(groupArtefacts: GroupArtefacts): HavePropertyMatchResult[Set[String]] = {
        val actualArtefacts = groupArtefacts.artefacts.toSet
        HavePropertyMatchResult(
          expectedArtefacts == actualArtefacts,
          "artefacts",
          expectedArtefacts,
          actualArtefacts
        )
      }
    }
}