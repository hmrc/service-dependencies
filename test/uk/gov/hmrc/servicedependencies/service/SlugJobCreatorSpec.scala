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
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.mockito.MockitoSugar
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito._
import org.scalatest.{FlatSpecLike, Matchers}
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.connector.ArtifactoryConnector
import uk.gov.hmrc.servicedependencies.connector.model.ArtifactoryChild
import uk.gov.hmrc.servicedependencies.model.NewSlugParserJob
import uk.gov.hmrc.servicedependencies.persistence.{SlugJobLastRunRepository, SlugParserJobsRepository}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits.global

class SlugJobCreatorSpec extends TestKit(ActorSystem("SlugJobCreatorSpec"))
  with ImplicitSender
  with FlatSpecLike
  with MockitoSugar
  with Matchers {


  "SlugJobCreator.add" should "write a number of mongojobs to the database" in {

    val boot = Boot.init

    val slug1 = ArtifactoryChild("/test-service", true)
    val slug2 = ArtifactoryChild("/abc", true)
    val slugJob = NewSlugParserJob("http://")

    when(boot.mockedArtifactoryConnector.findAllServices())
      .thenReturn(Future(List(slug1, slug2)))

    when(boot.mockedArtifactoryConnector.findAllSlugsForService("/test-service"))
      .thenReturn(Future(List(NewSlugParserJob("http://test-service/test-service_1.2.3-0.5.2.tgz"))))

    when(boot.mockedArtifactoryConnector.findAllSlugsForService("/abc"))
      .thenReturn(Future(List(NewSlugParserJob("http://abc/abc.2.3-0.5.2.tgz"))))

    when(boot.mockedSlugParserJobsRepository.add(any()))
      .thenReturn(Future(true))

    boot.slugJobCreator.runHistoric(limit = Some(1000))

    Thread.sleep(1000)
    verify(boot.mockedArtifactoryConnector, times(1)).findAllServices()
    verify(boot.mockedArtifactoryConnector, times(1)).findAllSlugsForService("/test-service")
    verify(boot.mockedArtifactoryConnector, times(1)).findAllSlugsForService("/abc")
  }

  case class Boot(
    mockedArtifactoryConnector    : ArtifactoryConnector,
    mockedSlugParserJobsRepository: SlugParserJobsRepository,
    mockedJobLastRunRepository    : SlugJobLastRunRepository,
    slugJobCreator                : SlugJobCreator
  )

  object Boot {
    def init: Boot = {
      val mockedArtifactoryConnector     = mock[ArtifactoryConnector]
      val mockedSlugParserJobsRepository = mock[SlugParserJobsRepository]
      val mockedJobLastRunRepository     = mock[SlugJobLastRunRepository]
      val configs                        = mock[SchedulerConfigs]
      val slugJobCreator = new SlugJobCreator(mockedArtifactoryConnector, mockedSlugParserJobsRepository, mockedJobLastRunRepository, configs)(ActorMaterializer()) {
        override val rateLimit: RateLimit = RateLimit(1000, FiniteDuration(10, "seconds"))
      }
      Boot(
        mockedArtifactoryConnector,
        mockedSlugParserJobsRepository,
        mockedJobLastRunRepository,
        slugJobCreator)
    }
  }
}
