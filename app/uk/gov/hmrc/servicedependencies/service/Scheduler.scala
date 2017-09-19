/*
 * Copyright 2017 HM Revenue & Customs
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

import akka.actor.{ActorSystem, Cancellable}
import com.google.inject.{Inject, Singleton}
import play.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import uk.gov.hmrc.servicedependencies.ServiceDependenciesController

import scala.concurrent.duration.{FiniteDuration, _}


@Singleton
class UpdateScheduler @Inject()(actorSystem: ActorSystem, serviceDependenciesController: ServiceDependenciesController) {

//  implicit val db: () => DefaultDB = mongo.mongoConnector.db

  def dependencyDataUpdatingService = serviceDependenciesController.dependencyDataUpdatingService

  private val timeStampGenerator = serviceDependenciesController.timeStampGenerator


  def startUpdatingLibraryDependencyData(interval: FiniteDuration): Cancellable = {
    Logger.info(s"Initialising libraryDependencyDataReloader update every $interval")

    val scheduler = actorSystem.scheduler.schedule(100 milliseconds, interval) {
      dependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories(timeStampGenerator)
    }

    scheduler
  }

  def startUpdatingLibraryData(interval: FiniteDuration): Cancellable = {
    Logger.info(s"Initialising libraryDataReloader update every $interval")

    val scheduler = actorSystem.scheduler.schedule(100 milliseconds, interval) {
      dependencyDataUpdatingService.reloadLatestLibraryVersions(timeStampGenerator)
    }

    scheduler
  }

  def startUpdatingSbtPluingVersionData(interval: FiniteDuration): Cancellable = {
    Logger.info(s"Initialising SbtPluginDataReloader update every $interval")

    val scheduler = actorSystem.scheduler.schedule(100 milliseconds, interval) {
      dependencyDataUpdatingService.reloadLatestSbtPluginVersions(timeStampGenerator)
    }

    scheduler
  }

}

