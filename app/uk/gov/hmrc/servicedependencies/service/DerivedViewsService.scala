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
import uk.gov.hmrc.servicedependencies.connector.model.Repository
import uk.gov.hmrc.servicedependencies.model.{MetaArtefactDependency, RepoType, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{Deployment, DeploymentRepository, MetaArtefactRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDeployedDependencyRepository, DerivedGroupArtefactRepository, DerivedLatestDependencyRepository, DerivedModuleRepository}
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicedependencies.model.MetaArtefact

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
, derivedDeployedDependencyRepository : DerivedDeployedDependencyRepository
, derivedLatestDependencyRepository   : DerivedLatestDependencyRepository
)(implicit ec: ExecutionContext
) extends Logging {

  def updateDeploymentDataForAllServices()(implicit hc: HeaderCarrier): Future[Unit] =
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

  def updateDerivedViews(repoName: String)(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      oActiveRepo <- teamsAndRepositoriesConnector.getRepository(repoName).map(_.filter(_.isArchived).toSeq)
      oLatestMeta <- metaArtefactRepository.find(repoName)
      deployments <- deploymentRepository.findDeployed(Some(repoName))
      _           <- updateDerivedDependencyViews(oActiveRepo.toSeq, oLatestMeta.toSeq, deployments)
      _           =  logger.info(s"Running DerivedModuleRepository.update")
      _           <- oLatestMeta.fold(Future.unit)(meta => derivedModuleRepository.update(meta))
      _           =  logger.info(s"Finished running DerivedModuleRepository.update")
    } yield ()

  def updateDerivedViewsForAllRepos()(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      activeRepos <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false))
      latestMeta  <- metaArtefactRepository.findLatest()
      deployments <- deploymentRepository.findDeployed()
      _           <- updateDerivedDependencyViews(activeRepos, latestMeta, deployments)
      _           =  logger.info(s"Running DerivedModuleRepository.populate")
      _           <- derivedModuleRepository
                       .populateAll()
                       .recover { case e => logger.error("Failed to update DerivedModuleRepository", e) }
      _           =  logger.info(s"Finished running DerivedModuleRepository.populate")
      _           =  logger.info(s"Running DerivedGroupArtefactRepository.populateAll")
      _           <- derivedGroupArtefactRepository
                       .populateAll()
                       .recover { case e => logger.error("Failed to update DerivedGroupArtefactRepository", e) }
      _           =  logger.info(s"Finished running DerivedGroupArtefactRepository.populateAll")
    } yield ()

  private def updateDerivedDependencyViews(activeRepos: Seq[Repository], latestMeta: Seq[MetaArtefact], deployments: Seq[Deployment] ): Future[Unit] =
    for {
      _ <- Future.unit
      _ =  logger.info(s"Running DerivedLatestDependencyRepository changes")
      _ <- latestMeta
             .flatMap(m => activeRepos.find(_.name == m.name).map(r => (m, r.repoType)))
             .foldLeftM(()) { case (_, (meta, repoType)) =>
               for {
                 ds <- derivedLatestDependencyRepository.find(repoName = Some(meta.name), repoVersion = Some(meta.version))
                 _  <- if (ds.isEmpty) derivedLatestDependencyRepository.delete(meta.name)
                       else            Future.unit
                 _  <- if (ds.isEmpty) derivedLatestDependencyRepository.put(
                                         DependencyGraphParser
                                           .parseMetaArtefact(meta)
                                           .map { case (node, scopes) => MetaArtefactDependency.apply(meta, repoType, node, scopes) }
                                           .toSeq
                                       )
                       else            Future.unit
               } yield ()
             }
      _ <- latestMeta
             .filterNot(m => activeRepos.exists(_.name == m.name))
             .foldLeftM(()) { (_, meta) => derivedLatestDependencyRepository.delete(meta.name)  }
      _ =  logger.info(s"Finished running DerivedLatestDependencyRepository changes")
      _ =  logger.info(s"Running DerivedDeployedDependencyRepository changes")
      _ <- deployments
             .groupBy(_.slugName)
             .toSeq
             .foldLeftM(()) { case (_, (slugName, slugDeployments)) =>
                derivedDeployedDependencyRepository.delete(slugName, ignoreVersions = slugDeployments.map(_.slugVersion))
             }
      _ <- deployments
             .filter(d => activeRepos.exists(_.name == d.slugName))
             .flatMap(d => d.flags.filterNot(_ == SlugInfoFlag.Latest).map(f => (d.slugName, d.slugVersion, f))) // Get all deployed versions
             .distinctBy { case (slugName, slugVersion, _) => (slugName, slugVersion) }                          // Flag just included for lookup but isn't needed
             .foldLeftM(()) { case (_, (slugName, slugVersion, flag)) =>
               for {
                 ds <- derivedDeployedDependencyRepository.find(flag = flag, slugName = Some(slugName), slugVersion = Some(slugVersion))
                 ms <- metaArtefactRepository.find(repositoryName = slugName, version = slugVersion)
                 _  <- (ds.isEmpty, ms.headOption) match {
                         case (true, Some(meta)) => derivedDeployedDependencyRepository.put(
                                                      DependencyGraphParser
                                                        .parseMetaArtefact(meta)
                                                        .map { case (node, scopes) => MetaArtefactDependency.apply(meta, RepoType.Service, node, scopes) }
                                                        .toSeq
                                                    )
                         case __                 => Future.unit
                       }
               } yield ()
             }
      _ <- deployments
             .filterNot(d => activeRepos.exists(_.name == d.slugName))
             .foldLeftM(()) { (_, deployment) => derivedDeployedDependencyRepository.delete(deployment.slugName)  }
      _ =  logger.info(s"Finished running DerivedDeployedDependencyRepository changes")
    } yield ()

}
