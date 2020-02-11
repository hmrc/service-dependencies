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

package uk.gov.hmrc.servicedependencies.scheduler

import akka.actor.ActorSystem
import com.google.inject.Singleton
import javax.inject.Inject
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.config.SchedulerConfigs
import uk.gov.hmrc.servicedependencies.persistence.MongoLocks
import uk.gov.hmrc.servicedependencies.service.DependencyDataUpdatingService
import uk.gov.hmrc.servicedependencies.util.SchedulerUtils

import scala.concurrent.ExecutionContext


@Singleton
class DataReloadScheduler @Inject()(
      schedulerConfigs             : SchedulerConfigs
    , dependencyDataUpdatingService: DependencyDataUpdatingService
    , mongoLocks                   : MongoLocks
    )(implicit
      actorSystem         : ActorSystem
    , applicationLifecycle: ApplicationLifecycle
    , ec                  : ExecutionContext
    ) extends SchedulerUtils {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleWithLock("dependencyDataReloader", schedulerConfigs.dependencyReload, mongoLocks.dependencyReloadSchedulerLock){
    dependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories()
      .map(_ => ())
  }

  scheduleWithLock("libraryDataReloader", schedulerConfigs.libraryReload, mongoLocks.libraryReloadSchedulerLock){
    for {
      _ <- dependencyDataUpdatingService.reloadLatestLibraryVersions()
      _ <- dependencyDataUpdatingService.reloadLatestSbtPluginVersions()
    } yield ()
  }
}
