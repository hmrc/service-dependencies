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

package uk.gov.hmrc.servicedependencies

import play.api.libs.json.Json
import uk.gov.hmrc.play.microservice.controller.BaseController
import play.api.mvc._
import play.libs.Akka
import uk.gov.hmrc.BlockingIOExecutionContext
import uk.gov.hmrc.githubclient.GithubApiClient

import scala.concurrent.Future

object ServiceDependenciesController extends ServiceDependenciesController {

	private val config = new ServiceDependenciesConfig()

	private val releasesConnector = new DeploymentsDataSource(config)
	private val teamsAndRepositoriesClient = new TeamsAndRepositoriesClient(config.teamsAndRepositoriesServiceUrl)

	private val gitEnterpriseClient = GithubApiClient(config.githubApiEnterpriseConfig.apiUrl, config.githubApiEnterpriseConfig.key)
	private val gitOpenClient = GithubApiClient(config.githubApiOpenConfig.apiUrl, config.githubApiOpenConfig.key)

	private class GithubOpen() extends Github(config.targetArtifact, config.buildFiles) {
		override val gh = gitOpenClient
		override def resolveTag(version: String) = s"v$version"
	}

	class GithubEnterprise() extends Github(config.targetArtifact, config.buildFiles) {
		override val gh = gitEnterpriseClient
		override def resolveTag(version: String) = s"release/$version"
	}

	private def dataLoader: () => Future[Seq[ServiceDependencies]] =
		new DependenciesDataSource(releasesConnector, teamsAndRepositoriesClient, Seq(new GithubEnterprise, new GithubOpen)).getDependencies _

	protected val dataSource = new CachingDependenciesDataSource(Akka.system(), config, dataLoader)
}

trait ServiceDependenciesController extends BaseController {
	import BlockingIOExecutionContext._

	protected val dataSource: CachingDependenciesDataSource

	implicit val evironmentDependencyWrites = Json.writes[EnvironmentDependency]
	implicit val serviceDependenciesWrites = Json.writes[ServiceDependencies]

	def get() = Action.async { implicit request =>
		dataSource.getCachedData.map { data => Ok(Json.toJson(data)) }
	}
}
