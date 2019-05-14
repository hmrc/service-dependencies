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

package uk.gov.hmrc.servicedependencies

import akka.actor.ActorSystem
import javax.inject.Inject
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.service.{SlugInfoService, SlugJobCreator, SlugJobProcessor}
import uk.gov.hmrc.servicedependencies.persistence.MongoLocks
import uk.gov.hmrc.servicedependencies.util.SchedulerUtils

import scala.concurrent.ExecutionContext


class SlugJobCreatorScheduler @Inject()(
    schedulerConfigs    : SchedulerConfigs,
    slugJobCreator      : SlugJobCreator,
    slugJobProcessor    : SlugJobProcessor,
    slugInfoService     : SlugInfoService,
    mongoLocks          : MongoLocks)(
    implicit actorSystem         : ActorSystem,
             applicationLifecycle: ApplicationLifecycle)
  extends SchedulerUtils {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  import ExecutionContext.Implicits.global
/*
  scheduleWithLock("Slug Info Reloader", schedulerConfigs.slugJobCreator, mongoLocks.slugJobSchedulerLock) {
    for {
      _ <- slugJobCreator.run
      _ = Logger.info("Finished creating slug jobs - now processing jobs")
      _ <- slugJobProcessor.run
      _ = Logger.info("Finished processing slug jobs - now updating meta data")
      _ <- slugInfoService.updateMetaData
      _ = Logger.info("Finished updating meta data")
    } yield ()
  }*/

}
