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

import org.mockito.MockitoSugar
import org.mongodb.scala.model.IndexModel
import org.scalatest.{Matchers, WordSpecLike}
import play.api.Configuration
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.mongo.throttle.ThrottleConfig
import uk.gov.hmrc.servicedependencies.model.GroupArtefacts
import uk.gov.hmrc.servicedependencies.persistence.TestSlugInfos._
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext.Implicits.global

class GroupArtefactsRepositorySpec
    extends WordSpecLike
      with Matchers
      with MockitoSugar
      // We don't mixin IndexedMongoQueriesSupport here, as this repo makes use of queries not satisfied by an index
      with CleanMongoCollectionSupport {

  val groupArtefactsRepo = new GroupArtefactRepository(mongoComponent)
  val throttleConfig     = new ThrottleConfig(Configuration())
  val slugInfoRepo       = new SlugInfoRepository(mongoComponent, throttleConfig)

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)

  override protected val collectionName: String   = groupArtefactsRepo.collectionName
  override protected val indexes: Seq[IndexModel] = groupArtefactsRepo.indexes

  "GroupArtefactsRepository.findGroupsArtefacts" should {
    "return a map of artefact group to list of found artefacts" in {
      slugInfoRepo.add(slugInfo).futureValue

      val result = groupArtefactsRepo.findGroupsArtefacts.futureValue

      result shouldBe Seq(GroupArtefacts("com.test.group", List("lib2", "lib1")))
    }
  }

}
