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
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, MetaArtefactRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDependencyRepository, DerivedGroupArtefactRepository, DerivedModuleRepository, DerivedServiceDependenciesRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DerivedViewsService @Inject()(
  teamsAndRepositoriesConnector       : TeamsAndRepositoriesConnector
, gitHubProxyConnector                : GitHubProxyConnector
, releasesApiConnector                : ReleasesApiConnector
, metaArtefactRepository              : MetaArtefactRepository
, slugInfoRepository                  : SlugInfoRepository
, slugVersionRepository               : SlugVersionRepository
, deploymentRepository                : DeploymentRepository
, derivedGroupArtefactRepository      : DerivedGroupArtefactRepository
, derivedModuleRepository             : DerivedModuleRepository
, derivedDependencyRepository         : DerivedDependencyRepository
, derivedServiceDependenciesRepository: DerivedServiceDependenciesRepository
)(implicit ec: ExecutionContext
) extends Logging {

  def updateDeploymentData()(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      slugNames              <- slugInfoRepository.getUniqueSlugNames()

      serviceDeploymentInfos <- releasesApiConnector.getWhatIsRunningWhere
      allServiceDeployments  =  slugNames.map { serviceName =>
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

      activeRepos            <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false))
                                  .map(_.map(_.name))
      latestServices         <- deploymentRepository.getNames(SlugInfoFlag.Latest)
      inactiveServices       =  latestServices.diff(activeRepos) // This will not work for slugs with different name to the repo (e.g. sa-filing-2223-helpdesk)
      _                      <- if (inactiveServices.nonEmpty) {
                                  logger.info(s"Removing latest flag from the following inactive services: ${inactiveServices.mkString(", ")}")
                                  // we have found some "archived" projects which are still deployed, we will only remove the latest flag for them
                                  deploymentRepository.clearFlags(List(SlugInfoFlag.Latest), inactiveServices.toList)
                                } else Future.unit

      decommissionedServices <- gitHubProxyConnector.decommissionedServices
      _                      <- deploymentRepository.clearFlags(SlugInfoFlag.values, decommissionedServices)

      missingLatestFlag      =  slugNames.intersect(activeRepos).diff(decommissionedServices).diff(latestServices)
      _                      <- if (missingLatestFlag.nonEmpty) {
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

  // TODO fix repo name lookup - it will delete if not found
  def updateDerivedViews()(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      activeRepos <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false))

      _           =  logger.info(s"Running DerivedDependencyRepository changes")
      latestMeta  <- metaArtefactRepository.findLatest()
      _           <- latestMeta
                       .flatMap(m => activeRepos.find(_.name == m.name).map(r => (m, r.repoType)))
                       .foldLeftM(()) { case (_, (meta, repoType)) =>
                         for {
                           ds <- derivedDependencyRepository.find(repoName = Some(meta.name), repoVersion = Some(meta.version))
                           _  <- if (ds.isEmpty) derivedDependencyRepository.delete(meta.name)
                                 else            Future.unit
                           _  <- if (ds.isEmpty) derivedDependencyRepository.put(
                                                   DependencyService
                                                     .parseMetaArtefact(meta)
                                                     .map { case (node, scopes) => MetaArtefactDependency.apply(meta, repoType, node, scopes) } // TODO move into parseMetaArtefact ??
                                                     .toSeq
                                                 )
                                 else            Future.unit
                         } yield ()
                       }
      _           <- latestMeta
                       .filterNot(m => activeRepos.exists(_.name == m.name))
                       .foldLeftM(()) { (_, meta) => derivedDependencyRepository.delete(meta.name)  }
      _           =  logger.info(s"Finished running DerivedDependencyRepository changes")

      _           =  logger.info(s"Running DerivedServiceDependenciesRepository changes")
      deployments <- deploymentRepository.findDeployed()
      _           <- deployments
                       .groupBy(_.slugName)
                       .toSeq
                       .foldLeftM(()) { case (_, (slugName, slugDeployments)) =>
                          derivedServiceDependenciesRepository.delete(slugName, ignoreVersions = slugDeployments.map(_.slugVersion))
                       }
      _           <- deployments
                       .filter(d => activeRepos.exists(_.name == d.slugName))
                       .flatMap(d => d.flags.filterNot(_ == SlugInfoFlag.Latest).map(f => (d.slugName, d.slugVersion, f))) // Get all deployed versions
                       .distinctBy { case (slugName, slugVersion, _) => (slugName, slugVersion) }                          // Flag just included for lookup but isn't needed
                       .foldLeftM(()) { case (_, (slugName, slugVersion, flag)) =>
                         for {
                           ds <- derivedServiceDependenciesRepository.find(flag = flag, slugName = Some(slugName), slugVersion = Some(slugVersion))
                           ms <- metaArtefactRepository.find(repositoryName = slugName, version = slugVersion)
                           _  <- (ds.isEmpty, ms.headOption) match {
                                   case (true, Some(meta)) => derivedServiceDependenciesRepository.put(
                                                                DependencyService
                                                                  .parseMetaArtefact(meta)
                                                                  .map { case (node, scopes) => MetaArtefactDependency.apply(meta, RepoType.Service, node, scopes) } // TODO move into parseMetaArtefact ??
                                                                  .toSeq
                                                              )
                                   case __                 => Future.unit
                                 }
                         } yield ()
                       }
      _           <- deployments
                       .filterNot(d => activeRepos.exists(_.name == d.slugName))
                       .foldLeftM(()) { (_, deployment) => derivedServiceDependenciesRepository.delete(deployment.slugName)  }
      _           =  logger.info(s"Finished running DerivedServiceDependenciesRepository changes")

      _           =  logger.info(s"Running DerivedGroupArtefactRepository.populate")
      _           <- derivedGroupArtefactRepository
                       .populateAll()
                       .recover { case e => logger.error("Failed to update DerivedGroupArtefactRepository", e) }
      _           =  logger.info(s"Finished running DerivedGroupArtefactRepository.populate")

      _           =  logger.info(s"Running DerivedModuleRepository.populate")
      _           <- derivedModuleRepository
                       .populateAll()
                       .recover { case e => logger.error("Failed to update DerivedModuleRepository", e) }
      _           =  logger.info(s"Finished running DerivedModuleRepository.populate")
    } yield ()

}
