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
import org.slf4j.LoggerFactory
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceDeploymentsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model.{DependencyConfig, GroupArtefacts, NewSlugParserJob, ServiceDependency, SlugInfo, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{DependencyConfigRepository, SlugInfoRepository, SlugParserJobsRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugParserJobsRepository      : SlugParserJobsRepository,
  slugInfoRepository            : SlugInfoRepository,
  dependencyConfigRepository    : DependencyConfigRepository,
  teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector,
  serviceDeploymentsConnector   : ServiceDeploymentsConnector
) {
  import ExecutionContext.Implicits.global

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  def addSlugParserJob(newJob: NewSlugParserJob): Future[Boolean] =
    slugParserJobsRepository.add(newJob)

  def getSlugInfos(name: String, version: Option[String]): Future[Seq[SlugInfo]] =
    slugInfoRepository.getSlugInfos(name, version)

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(name, flag)

  def findServicesWithDependency(flag: SlugInfoFlag, group: String, artefact: String)(implicit hc: HeaderCarrier): Future[Seq[ServiceDependency]] =
    for {
      res              <- slugInfoRepository.findServices(flag, group, artefact)
      teamsForServices <- teamsAndRepositoriesConnector.getTeamsForServices
    } yield res.map { r =>
        r.copy(teams = teamsForServices.getTeams(r.slugName).toList)
      }

  def findGroupsArtefacts: Future[Seq[GroupArtefacts]] =
    slugInfoRepository
      .findGroupsArtefacts

  def updateMetaData(implicit hc: HeaderCarrier): Future[Unit] = {
    import ServiceDeploymentsConnector._
    for {
      serviceDeploymentInfos <- serviceDeploymentsConnector.getWhatIsRunningWhere
      _                      <- Future.sequence {
                                  serviceDeploymentInfos.flatMap {
                                    case ServiceDeploymentInformation(serviceName, deployments) =>
                                      deployments.map {
                                        case Deployment(Some(Environment.Production), version) => slugInfoRepository.markProduction(serviceName, version)
                                        case Deployment(Some(Environment.QA        ), version) => slugInfoRepository.markQa(serviceName, version)
                                        case Deployment(optEnv, version)                       => Future(())
                                      }
                                  }
                                }
    } yield ()
  }

  def findDependencyConfig(group: String, artefact: String, version: String): Future[Option[DependencyConfig]] =
    dependencyConfigRepository.getDependencyConfig(group, artefact, version)
}
