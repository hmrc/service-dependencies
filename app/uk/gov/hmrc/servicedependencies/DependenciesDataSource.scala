/*
 * Copyright 2016 HM Revenue & Customs
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
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

case class EnvironmentDependency(version: String, dependencyVersion: String)
case class ServiceDependencies(name: String, environments: Map[String, EnvironmentDependency])

class DependenciesDataSource(val releasesConnector: DeploymentsDataSource, val githubs: Seq[Github]) {
  def getDependencies: Future[Seq[ServiceDependencies]] =
    for {
      services <- releasesConnector.listOfRunningServices()
    } yield services.map(serviceVersions)

  private def serviceVersions(service: Service): ServiceDependencies = {
    val environmentVersions = Map("qa" -> service.qaVersion, "staging" -> service.stagingVersion, "prod" -> service.prodVersion)
    val versions = environmentVersions.values.toSeq
      .distinct
      .map { v => v -> searchGithubsForArtifact(service.name, v).map(_.toString).getOrElse("N/A") }.toMap

    ServiceDependencies(
      service.name,
      environmentVersions
        .filter { case (x, y) => y.nonEmpty }
        .map { case (x, y) => x -> new EnvironmentDependency(y.get, versions(y)) })
  }

  private def searchGithubsForArtifact(serviceName: String, version: Option[String]): Option[Version] = {
    githubs.foreach(x => x.findArtifactVersion(serviceName, version) match {
      case Some(v) => return Some(v)
      case _ =>
    })
    None
  }
}

class CachingDependenciesDataSource(akkaSystem: ActorSystem, cacheConfig: CacheConfig, dataSource: () => Future[Seq[ServiceDependencies]]) {
  private var cachedData: Option[Seq[ServiceDependencies]] = None
  private val initialPromise = Promise[Seq[ServiceDependencies]]()

  import ExecutionContext.Implicits._

  dataUpdate()

  def getCachedData: Future[Seq[ServiceDependencies]] = {
    Logger.info(s"cachedData is available = ${cachedData.isDefined}")
    if (cachedData.isEmpty && initialPromise.isCompleted) {
      Logger.warn("in unexpected state where initial promise is complete but there is not cached data. Perform manual reload.")
    }
    cachedData.fold(initialPromise.future)(d => Future.successful(d))
  }

  def reload() = {
    Logger.info(s"Manual cache reload triggered")
    dataUpdate()
  }

  Logger.info(s"Initialising cache reload every ${cacheConfig.cacheDuration}")
  akkaSystem.scheduler.schedule(cacheConfig.cacheDuration, cacheConfig.cacheDuration) {
    Logger.info("Scheduled cache reload triggered")
    dataUpdate()
  }

  private def dataUpdate() {
    dataSource().onComplete {
      case Failure(e) => Logger.warn(s"failed to get latest data due to ${e.getMessage}", e)
      case Success(d) => {
        synchronized {
          this.cachedData = Some(d)
          Logger.info(s"data update completed successfully")

          if (!initialPromise.isCompleted) {
            Logger.debug("early clients being sent result")
            this.initialPromise.success(d)
          }
        }
      }
    }
  }

}
