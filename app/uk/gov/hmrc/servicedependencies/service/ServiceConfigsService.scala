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

package uk.gov.hmrc.servicedependencies.service

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency, DependencyBobbyRule}
import uk.gov.hmrc.servicedependencies.model.{BobbyRule, BobbyRules}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceConfigsService @Inject()(serviceConfigsConnector: ServiceConfigsConnector)(implicit ec: ExecutionContext) {

  import ServiceConfigsService._

  def getDependenciesWithBobbyRules(dependencies: Dependencies): Future[Dependencies] =
    serviceConfigsConnector.getBobbyRules.map { bobbyRules =>
      dependencies.copy(
        libraryDependencies    = dependencies.libraryDependencies.map(enrichWithBobbyRuleViolations(bobbyRules))
      , sbtPluginsDependencies = dependencies.sbtPluginsDependencies.map(enrichWithBobbyRuleViolations(bobbyRules))
      , otherDependencies      = dependencies.otherDependencies.map(enrichWithBobbyRuleViolations(bobbyRules))
      )
    }

  /*
   * For consistency with above - but would prefer to simply return a mapping of dependency to violations.
   */
  def getDependenciesWithBobbyRules(dependencies: List[Dependency]): Future[List[Dependency]] =
    serviceConfigsConnector.getBobbyRules.map { bobbyRules =>
      dependencies.map(enrichWithBobbyRuleViolations(bobbyRules))
    }
}

private object ServiceConfigsService {
  def enrichWithBobbyRuleViolations(bobbyRules: BobbyRules)(dependency: Dependency): Dependency =
    dependency.copy(
      bobbyRuleViolations = bobbyRules.violationsFor(dependency)
    )
}