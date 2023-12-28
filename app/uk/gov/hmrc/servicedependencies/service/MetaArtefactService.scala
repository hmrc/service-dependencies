/*
 * Copyright 2023 HM Revenue & Customs
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

import cats.instances.all._
import cats.syntax.all._
import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{GitHubProxyConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, SlugInfoRepository, SlugVersionRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetaArtefactService @Inject()(
  slugInfoRepository            : SlugInfoRepository,
  slugVersionRepository         : SlugVersionRepository,
  deploymentRepository          : DeploymentRepository,
  teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector,
  releasesApiConnector          : ReleasesApiConnector,
  gitHubProxyConnector          : GitHubProxyConnector,
)(implicit ec: ExecutionContext
) extends Logging {

  def updateMetadata()(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      serviceNames           <- slugInfoRepository.getUniqueSlugNames
      serviceDeploymentInfos <- releasesApiConnector.getWhatIsRunningWhere
      activeRepos            <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false))
                                  .map(_.map(_.name))
      decomissionedServices  <- gitHubProxyConnector.decomissionedServices
      latestServices         <- deploymentRepository.getNames(SlugInfoFlag.Latest)
      inactiveServices       =  latestServices.diff(activeRepos) // This will not work for slugs with different name to the repo (e.g. sa-filing-2223-helpdesk)
      allServiceDeployments  =  serviceNames.map { serviceName =>
                                  val deployments       = serviceDeploymentInfos.find(_.serviceName == serviceName).map(_.deployments)
                                  val deploymentsByFlag = List( (SlugInfoFlag.Production    , ReleasesApiConnector.Environment.Production)
                                                              , (SlugInfoFlag.QA            , ReleasesApiConnector.Environment.QA)
                                                              , (SlugInfoFlag.Staging       , ReleasesApiConnector.Environment.Staging)
                                                              , (SlugInfoFlag.Development   , ReleasesApiConnector.Environment.Development)
                                                              , (SlugInfoFlag.ExternalTest  , ReleasesApiConnector.Environment.ExternalTest)
                                                              , (SlugInfoFlag.Integration   , ReleasesApiConnector.Environment.Integration)
                                                              )
                                                           .map { case (flag, env) =>
                                                                    ( flag
                                                                    , deployments.flatMap(
                                                                          _.find(_.optEnvironment.contains(env))
                                                                           .map(_.version)
                                                                        )
                                                                    )
                                                                }
                                  (serviceName, deploymentsByFlag)
                                }
      _                      <- allServiceDeployments.toList.traverse { case (serviceName, deployments) =>
                                  deployments.traverse {
                                    case (flag, None         ) => deploymentRepository.clearFlag(flag, serviceName)
                                    case (flag, Some(version)) => deploymentRepository.setFlag(flag, serviceName, version)
                                  }
                                }
      _                      <- deploymentRepository.clearFlags(SlugInfoFlag.values, decomissionedServices)
      _                      <- if (inactiveServices.nonEmpty) {
                                  logger.info(s"Removing latest flag from the following inactive services: ${inactiveServices.mkString(", ")}")
                                  // we have found some "archived" projects which are still deployed, we will only remove the latest flag for them
                                  deploymentRepository.clearFlags(List(SlugInfoFlag.Latest), inactiveServices.toList)
                                } else Future.unit
      missingLatestFlag      =  serviceNames.intersect(activeRepos).diff(decomissionedServices).diff(latestServices)
      _                      <-  if (missingLatestFlag.nonEmpty) {
                                  logger.warn(s"The following services are missing Latest flag - and will be added: ${missingLatestFlag.mkString(",")}")
                                  missingLatestFlag.foldLeftM(()) { (_, serviceName) =>
                                    for {
                                      optVersion <- slugVersionRepository.getMaxVersion(serviceName)
                                      _          <- optVersion match {
                                                      case Some(version) => deploymentRepository.setFlag(SlugInfoFlag.Latest, serviceName, version)
                                                      case None          => logger.warn(s"No max version found for $serviceName"); Future.unit
                                                    }
                                    } yield ()
                                  }
                                } else Future.unit
    } yield ()

}