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
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.scalatest.{FlatSpecLike, Matchers}
import uk.gov.hmrc.servicedependencies.connector.ArtifactoryConnector
import uk.gov.hmrc.servicedependencies.connector.model.{ArtifactoryChild, ArtifactoryRepo}
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, NewSlugParserJob}
import uk.gov.hmrc.servicedependencies.persistence.SlugParserJobsRepository
import org.mockito.ArgumentMatchers.{any, eq => eqTo}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits.global

class SlugUpdaterSpec extends TestKit(ActorSystem("MySpec"))
  with ImplicitSender
  with FlatSpecLike
  with MockitoSugar
  with Matchers {

  val mockRepo = mock[SlugParserJobsRepository]
  val mockConnector = mock[ArtifactoryConnector]



  "update" should "write a number of mongojobs to the database" in {

    val slug1 = ArtifactoryChild("/test-service", true)
    val slug2 = ArtifactoryChild("/abc", true)
    val allRepos = ArtifactoryRepo("webstore", "2018-04-30T09:06:22.544Z", "2018-04-30T09:06:22.544Z", Seq(slug1, slug2))

    val slugJob = NewSlugParserJob("http://")

    when(mockConnector.findAllSlugs()).thenReturn(Future(List(slug1, slug2)))
    when(mockConnector.findAllSlugsForService(any())).thenReturn(Future(List(slugJob, slugJob)))
    when(mockRepo.add(any())).thenReturn(Future())

    val slugUpdater = new SlugUpdater(mockConnector, mockRepo, RateLimit(10000, FiniteDuration(100, "seconds")), ActorMaterializer())

    slugUpdater.update()

    Thread.sleep(1000)
    verify(mockRepo, times(4)).add(any())

  }

}
