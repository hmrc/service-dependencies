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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpecLike, Matchers}
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.connector.ArtifactoryConnector
import uk.gov.hmrc.servicedependencies.model.NewSlugParserJob
import uk.gov.hmrc.servicedependencies.persistence.{SlugJobLastRunRepository, SlugParserJobsRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlugJobCreatorSpec extends TestKit(ActorSystem("SlugJobCreatorSpec"))
  with ImplicitSender
  with FlatSpecLike
  with MockitoSugar
  with Matchers {

  "SlugJobCreator.runBackfill" should "write a number of mongojobs to the database" in {

    val boot = Boot.init

    val slug1 = NewSlugParserJob("http://abc/abc.2.3-0.5.2.tgz")
    val slug2 = NewSlugParserJob("http://test-service/test-service_1.2.3-0.5.2.tgz")
    when(boot.mockedArtifactoryConnector.findSlugsForBackFill(any()))
      .thenReturn(Future(List(slug1, slug2)))

    when(boot.mockedSlugParserJobsRepository.add(any()))
      .thenReturn(Future(true))

    boot.slugJobCreator.runBackfill

    Thread.sleep(1000)
    verify(boot.mockedSlugParserJobsRepository).add(slug1)
    verify(boot.mockedSlugParserJobsRepository).add(slug2)
    verify(boot.mockedArtifactoryConnector).findSlugsForBackFill(any())
  }

  it should "only select the latest version to backfill" in {

    val boot = Boot.init

    val slug020 =  NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/address-lookup/address-lookup_0.3.0_0.5.2.tgz")
    val slug120 =  NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/address-lookup/address-lookup_1.2.0_0.5.2.tgz")
    val slug130 =  NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/address-lookup/address-lookup_1.3.0_0.5.2.tgz")
    val slug131 =  NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/address-lookup/address-lookup_1.3.1_0.5.2.tgz")

    when(boot.mockedArtifactoryConnector.findSlugsForBackFill(any()))
      .thenReturn(Future(List(slug130, slug020, slug120, slug131)))

    when(boot.mockedSlugParserJobsRepository.add(any()))
      .thenReturn(Future(true))

    boot.slugJobCreator.runBackfill

    Thread.sleep(1000)
    verify(boot.mockedSlugParserJobsRepository, times(1)).add(slug131)
    verify(boot.mockedArtifactoryConnector, times(1)).findSlugsForBackFill(any())
  }


  it should "treat single digit versioned slugs as lower than sem-ver slugs" in {

    val boot = Boot.init

    val slug010 = NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/auth-agents-stub/auth-agents-stub_0.1.0_0.5.2.tgz")
    val slug43 =  NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/auth-agents-stub/auth-agents-stub_43_0.5.2.tgz")
    val slug52 =  NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/auth-agents-stub/auth-agents-stub_52_0.5.2.tgz")
    val slug53 =  NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/auth-agents-stub/auth-agents-stub_53_0.5.2.tgz")

    when(boot.mockedArtifactoryConnector.findSlugsForBackFill(any()))
      .thenReturn(Future(List(slug52, slug010, slug43, slug53)))

    when(boot.mockedSlugParserJobsRepository.add(any()))
      .thenReturn(Future(true))

    boot.slugJobCreator.runBackfill

    Thread.sleep(1000)
    verify(boot.mockedSlugParserJobsRepository, times(1)).add(slug010.copy(processed = false))
    verify(boot.mockedSlugParserJobsRepository, times(1)).add(slug43.copy(processed = true))
    verify(boot.mockedSlugParserJobsRepository, times(1)).add(slug52.copy(processed = true))
    verify(boot.mockedSlugParserJobsRepository, times(1)).add(slug53.copy(processed = true))

    verify(boot.mockedArtifactoryConnector, times(1)).findSlugsForBackFill(any())
  }

  it should "treat suffixed sem-ver versions as lower than a suffixed version with the same version" in {

    val boot = Boot.init

    val suffixed = NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/cgt-calculator/cgt-calculator_1.38.0-3-g8117097_0.5.2.tgz")
    val semvered = NewSlugParserJob("https://artifact.store.uk/artifactory/webstore/slugs/cgt-calculator/cgt-calculator_1.38.0_0.5.2.tgz")


    when(boot.mockedArtifactoryConnector.findSlugsForBackFill(any()))
      .thenReturn(Future(List(semvered, suffixed)))

    when(boot.mockedSlugParserJobsRepository.add(any()))
      .thenReturn(Future(true))

    boot.slugJobCreator.runBackfill

    Thread.sleep(1000)
    verify(boot.mockedSlugParserJobsRepository).add(semvered.copy(processed = false))
    verify(boot.mockedSlugParserJobsRepository).add(suffixed.copy(processed = true))

    verify(boot.mockedArtifactoryConnector, times(1)).findSlugsForBackFill(any())
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

      }
      Boot(
        mockedArtifactoryConnector,
        mockedSlugParserJobsRepository,
        mockedJobLastRunRepository,
        slugJobCreator)
    }
  }
}
