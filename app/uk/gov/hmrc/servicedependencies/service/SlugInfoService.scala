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
import uk.gov.hmrc.servicedependencies.connector.{GithubRawConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, JdkVersionRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedGroupArtefactRepository, DerivedServiceDependenciesRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository            : SlugInfoRepository,
  slugVersionRepository         : SlugVersionRepository,
  serviceDependencyRepository   : DerivedServiceDependenciesRepository,
  jdkVersionRepository          : JdkVersionRepository,
  groupArtefactRepository       : DerivedGroupArtefactRepository,
  deploymentRepository          : DeploymentRepository,
  teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector,
  releasesApiConnector          : ReleasesApiConnector,
  githubRawConnector            : GithubRawConnector,
)(implicit ec: ExecutionContext
) extends Logging {

  def addSlugInfo(slug: SlugInfo, metaArtefact: Option[MetaArtefact]): Future[Unit] =
    for {
      // Determine which slug is latest from the existing collection
      _        <- slugInfoRepository.add(slug)
      isLatest <- slugVersionRepository.getMaxVersion(name = slug.name)
                    .map {
                      case None             => true
                      case Some(maxVersion) => val isLatest = maxVersion == slug.version
                                               logger.info(s"Slug ${slug.name} ${slug.version} isLatest=$isLatest (latest is: ${maxVersion})")
                                               isLatest
                    }
      _        <- if (isLatest) deploymentRepository.markLatest(slug.name, slug.version) else Future.unit
      _        <- serviceDependencyRepository.populateDependencies(slug, metaArtefact)
    } yield ()

  def deleteSlugInfo(name: String, version: Version): Future[Unit] =
    for {
      _ <- deploymentRepository.delete(name, version)
      _ <- serviceDependencyRepository.delete(name, version)
      _ <- slugInfoRepository.delete(name, version)
    } yield ()

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(name, flag)

  def getSlugInfo(name: String, version: Version): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(name, version)

  def findServicesWithDependency(
      flag        : SlugInfoFlag
    , group       : String
    , artefact    : String
    , versionRange: BobbyVersionRange
    , scopes      : Option[List[DependencyScope]]
    )(implicit hc: HeaderCarrier): Future[Seq[ServiceDependency]] =
      for {
        services            <- serviceDependencyRepository.findServicesWithDependency(flag, group, artefact, scopes)
        servicesWithinRange =  services.filter(s => versionRange.includes(s.depVersion))
        teamsForServices    <- teamsAndRepositoriesConnector.getTeamsForServices
      } yield servicesWithinRange.map { r =>
          r.copy(teams = teamsForServices.getTeams(r.slugName).toList.sorted)
        }

  def findGroupsArtefacts: Future[Seq[GroupArtefacts]] =
    groupArtefactRepository
      .findGroupsArtefacts

  def updateMetadata()(implicit hc: HeaderCarrier): Future[Unit] = {
    import ReleasesApiConnector._
    for {
      serviceNames           <- slugInfoRepository.getUniqueSlugNames
      serviceDeploymentInfos <- releasesApiConnector.getWhatIsRunningWhere
      activeRepos            <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false))
                                  .map(_.map(_.name))
      decomissionedServices  <- githubRawConnector.decomissionedServices
      latestServices         <- deploymentRepository.getNames(SlugInfoFlag.Latest)
      inactiveServices       =  latestServices.diff(activeRepos) // This will not work for slugs with different name to the repo (e.g. sa-filing-2223-helpdesk)
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
                                  missingLatestFlag.traverse { serviceName =>
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

  def findJDKVersions(teamName: Option[String], flag: SlugInfoFlag)(implicit hc: HeaderCarrier): Future[Seq[JDKVersion]] =
    teamName match {
      case Some(n) => for {
                        team <- teamsAndRepositoriesConnector.getTeam(n)
                        xs   <- jdkVersionRepository.findJDKUsage(flag)
                      } yield xs.filter(x => team.services.contains(x.name))
      case None    => jdkVersionRepository.findJDKUsage(flag)
    }
}
