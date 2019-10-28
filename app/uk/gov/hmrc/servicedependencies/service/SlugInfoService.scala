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

import cats.instances.all._
import cats.syntax.all._
import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceDeploymentsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{GroupArtefactRepository, JdkVersionRepository, ServiceDependencyRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
 slugInfoRepository            : SlugInfoRepository,
 serviceDependencyRepository   : ServiceDependencyRepository,
 jdkVersionRepository          : JdkVersionRepository,
 groupArtefactRepository       : GroupArtefactRepository,
 teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector,
 serviceDeploymentsConnector   : ServiceDeploymentsConnector
) {
  import ExecutionContext.Implicits.global

  def addSlugInfo(slug: SlugInfo): Future[Boolean] = {
    for {
      added <- slugInfoRepository.add(slug)
      _ <- if (slug.latest) slugInfoRepository.markLatest(slug.name, slug.version) else Future(())
    } yield added
  }

  def getSlugInfos(name: String, version: Option[String]): Future[Seq[SlugInfo]] =
    slugInfoRepository.getSlugInfos(name, version)

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(name, flag)

  def findServicesWithDependency(
      flag        : SlugInfoFlag
    , group       : String
    , artefact    : String
    , versionRange: BobbyVersionRange
    )(implicit hc: HeaderCarrier): Future[Seq[ServiceDependency]] =
      for {
        services            <- serviceDependencyRepository.findServices(flag, group, artefact)
        servicesWithinRange =  services.filter(_.depSemanticVersion.map(versionRange.includes).getOrElse(true)) // include invalid semanticVersion in results
        teamsForServices    <- teamsAndRepositoriesConnector.getTeamsForServices
      } yield servicesWithinRange.map { r =>
          r.copy(teams = teamsForServices.getTeams(r.slugName).toList.sorted)
        }

  def findGroupsArtefacts: Future[Seq[GroupArtefacts]] =
    groupArtefactRepository
      .findGroupsArtefacts

  def updateMetadata()(implicit hc: HeaderCarrier): Future[Unit] = {

    import ServiceDeploymentsConnector._
    for {
      serviceNames           <- slugInfoRepository.getUniqueSlugNames
      serviceDeploymentInfos <- serviceDeploymentsConnector.getWhatIsRunningWhere
      allServiceDeployments  =  serviceNames.map { serviceName =>
                                  val deployments       = serviceDeploymentInfos.find(_.serviceName == serviceName).map(_.deployments)
                                  val deploymentsByFlag = List( (SlugInfoFlag.Production    , Environment.Production)
                                                              , (SlugInfoFlag.QA            , Environment.QA)
                                                              , (SlugInfoFlag.Staging       , Environment.Staging)
                                                              , (SlugInfoFlag.Development   , Environment.Development)
                                                              , (SlugInfoFlag.ExternalTest  , Environment.ExternalTest)
                                                              , (SlugInfoFlag.Integration   , Environment.Integration)
                                                              )
                                                           .map { case (flag, env) =>
                                                                    ( flag
                                                                    , deployments.flatMap(
                                                                          _.find(_.optEnvironment == Some(env))
                                                                            .map(_.version)
                                                                        )
                                                                    )
                                                                }
                                  (serviceName, deploymentsByFlag)
                                }
      _                      <- allServiceDeployments.toList.traverse { case (serviceName, deployments) =>
                                  deployments.traverse {
                                    case (flag, None         ) => slugInfoRepository.clearFlag(flag, serviceName)
                                    case (flag, Some(version)) => slugInfoRepository.setFlag(flag, serviceName, version)
                                  }
                                }
    } yield ()
  }

  def findJDKVersions(flag: SlugInfoFlag): Future[Seq[JDKVersion]] =
    jdkVersionRepository.findJDKUsage(flag)
}