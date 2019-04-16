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
import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.connector.model.BobbyRule
import uk.gov.hmrc.servicedependencies.controller.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceConfigsService @Inject()(serviceConfigsConnector: ServiceConfigsConnector) {

  import ExecutionContext.Implicits.global

  def getDependenciesWithBobbyRules(dependencies: Dependencies): Future[Dependencies] =
    serviceConfigsConnector
      .getBobbyRules()
      .map(groupedDeps => {

        val library = dependencies.libraryDependencies.map(addBobbyViolations(groupedDeps, _))
        val plugin  = dependencies.sbtPluginsDependencies.map(addBobbyViolations(groupedDeps, _))
        val other   = dependencies.otherDependencies.map(addBobbyViolations(groupedDeps, _))

        dependencies
          .copy(libraryDependencies = library, sbtPluginsDependencies = plugin, otherDependencies = other)
      })

  private def addBobbyViolations(
    groupedBobbyRules: Map[String, List[BobbyRule]],
    dependency: Dependency): Dependency = {
    val bobbyRules = groupedBobbyRules
      .getOrElse(s"${dependency.name}", List())
      .filter(_.range.includes(dependency.currentVersion))
      .map(_.asDependencyBobbyRule())

    dependency.copy(bobbyRuleViolations = bobbyRules)
  }
}
