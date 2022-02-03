/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.controller.admin

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, LocksRepository, RepositoryDependenciesRepository}
import uk.gov.hmrc.servicedependencies.service.DependencyDataUpdatingService

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AdministrationController @Inject()(
    dependencyDataUpdatingService   : DependencyDataUpdatingService
  , locksRepository                 : LocksRepository
  , repositoryDependenciesRepository: RepositoryDependenciesRepository
  , latestVersionRepository         : LatestVersionRepository
  , cc                              : ControllerComponents
  )(implicit ec: ExecutionContext
  ) extends BackendController(cc) {

  def reloadLatestVersions =
    Action {
      dependencyDataUpdatingService
        .reloadLatestVersions
        .recoverWith {
          case ex => Future.failed(new RuntimeException("reload of dependency versions failed", ex))
        }
      Accepted("reload started")
    }

  def dropCollection(collection: String) =
    Action.async {
      (collection match {
         case "locks"                         => locksRepository.clearAllData
         case "repositoryLibraryDependencies" => repositoryDependenciesRepository.clearAllData
         case "dependencyVersions"            => latestVersionRepository.clearAllData
         case other                           => sys.error(s"dropping $other collection is not supported")
       }
      ).map(_ => Ok(s"$collection dropped"))
    }

  def clearUpdateDates =
    Action.async {
      repositoryDependenciesRepository
        .clearUpdateDates
        .map(rs => Ok(s"${rs.size} records updated"))
    }

  def clearUpdateDatesForRepository(repositoryName: String) =
    Action.async {
      repositoryDependenciesRepository
        .clearUpdateDatesForRepository(repositoryName)
        .map {
          case None    => NotFound("")
          case Some(_) => Ok(s"record updated")
        }
    }

  def mongoLocks() =
    Action.async {
      locksRepository.getAllEntries.map(locks => Ok(Json.toJson(locks)))
    }
}
