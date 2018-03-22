/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.{Success, Try}

@Singleton
class UpdateScheduler @Inject()(
  actorSystem: ActorSystem,
  dependencyDataUpdatingService: DependencyDataUpdatingService) {

  def startUpdatingLibraryDependencyData(interval: FiniteDuration)(implicit hc: HeaderCarrier): Cancellable = {
    Logger.info(s"Initialising libraryDependencyDataReloader update every $interval")

    val scheduler = actorSystem.scheduler.schedule(100 milliseconds, interval) {
      run(dependencyDataUpdatingService.reloadCurrentDependenciesDataForAllRepositories()).recover {
        case e: Throwable => Logger.error(s"Library dependencies update interrupted because: ${e.getMessage}")
      }
    }

    scheduler
  }

  def startUpdatingLibraryData(interval: FiniteDuration)(implicit hc: HeaderCarrier): Cancellable = {
    Logger.info(s"Initialising libraryDataReloader update every $interval")

    val scheduler = actorSystem.scheduler.schedule(100 milliseconds, interval) {
      run(dependencyDataUpdatingService.reloadLatestLibraryVersions()).recover {
        case e: Throwable => Logger.error(s"Libraries version update interrupted because: ${e.getMessage}")
      }
    }

    scheduler
  }

  def startUpdatingSbtPluingVersionData(interval: FiniteDuration)(implicit hc: HeaderCarrier): Cancellable = {
    Logger.info(s"Initialising SbtPluginDataReloader update every $interval")

    val scheduler = actorSystem.scheduler.schedule(100 milliseconds, interval) {
      run(dependencyDataUpdatingService.reloadLatestSbtPluginVersions()).recover {
        case e: Throwable => Logger.error(s"Sbt Plugins version update interrupted because: ${e.getMessage}")
      }
    }

    scheduler
  }

  def run[B](fn: => Future[B]): Future[B] =
    Try(fn) match {
      case Success(result)               => result
      case scala.util.Failure(throwable) => Future.failed(throwable)
    }

}
