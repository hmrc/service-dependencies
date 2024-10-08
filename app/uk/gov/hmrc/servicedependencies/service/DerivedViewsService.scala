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
import uk.gov.hmrc.servicedependencies.connector.{ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model.{MetaArtefactDependency, RepoType, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.{Deployment, DeploymentRepository, MetaArtefactRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDeployedDependencyRepository, DerivedGroupArtefactRepository, DerivedLatestDependencyRepository, DerivedModuleRepository}
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicedependencies.model.MetaArtefact

@Singleton
class DerivedViewsService @Inject()(
  teamsAndRepositoriesConnector       : TeamsAndRepositoriesConnector
, releasesApiConnector                : ReleasesApiConnector
, metaArtefactRepository              : MetaArtefactRepository
, slugInfoRepository                  : SlugInfoRepository
, slugVersionRepository               : SlugVersionRepository
, deploymentRepository                : DeploymentRepository
, derivedGroupArtefactRepository      : DerivedGroupArtefactRepository
, derivedModuleRepository             : DerivedModuleRepository
, derivedDeployedDependencyRepository : DerivedDeployedDependencyRepository
, derivedLatestDependencyRepository   : DerivedLatestDependencyRepository
)(using ec: ExecutionContext
) extends Logging:

  def updateDeploymentDataForAllServices()(using hc: HeaderCarrier): Future[Unit] =
    for
      slugNames              <- slugInfoRepository.getUniqueSlugNames()

      serviceDeploymentInfos <- releasesApiConnector.getWhatIsRunningWhere()
      allServiceDeployments  =  slugNames.map: serviceName =>
                                  val deployments       = serviceDeploymentInfos.find(_.serviceName == serviceName).map(_.deployments)
                                  val deploymentsByFlag = List( (SlugInfoFlag.Production    , ReleasesApiConnector.Environment.Production)
                                                              , (SlugInfoFlag.QA            , ReleasesApiConnector.Environment.QA)
                                                              , (SlugInfoFlag.Staging       , ReleasesApiConnector.Environment.Staging)
                                                              , (SlugInfoFlag.Development   , ReleasesApiConnector.Environment.Development)
                                                              , (SlugInfoFlag.ExternalTest  , ReleasesApiConnector.Environment.ExternalTest)
                                                              , (SlugInfoFlag.Integration   , ReleasesApiConnector.Environment.Integration)
                                                              )
                                                           .map: (flag, env) =>
                                                              ( flag
                                                              , deployments.flatMap:
                                                                    _.find(_.optEnvironment.contains(env))
                                                                     .map(_.version)
                                                              )
                                  (serviceName, deploymentsByFlag)
      _                      <- allServiceDeployments
                                  .flatMap:
                                    case (serviceName, deployments) => deployments.map((flag, optVersion) => (serviceName, flag, optVersion))
                                  .foldLeftM(()):
                                    case (_, (serviceName, flag, None         )) => deploymentRepository.clearFlag(flag, serviceName)
                                    case (_, (serviceName, flag, Some(version))) => deploymentRepository.setFlag(flag, serviceName, version)
      activeRepos            <- teamsAndRepositoriesConnector
                                  .getAllRepositories(archived = Some(false))
                                  .map(_.map(_.name))
      latestServices         <- deploymentRepository.getNames(SlugInfoFlag.Latest)
      inactiveServices       =  latestServices // check if deployed too since repo name may not match service name
                                  .diff(activeRepos)
                                  .filterNot: serviceName =>
                                    serviceDeploymentInfos.exists(x => x.serviceName == serviceName && x.deployments.nonEmpty)
      _                      <-
                                if inactiveServices.nonEmpty then
                                  logger.info(s"Removing latest flag from the following inactive services: ${inactiveServices.mkString(", ")}")
                                  // we have found some "archived" projects which are still deployed, we will only remove the latest flag for them
                                  deploymentRepository.clearFlags(List(SlugInfoFlag.Latest), inactiveServices.toList)
                                else Future.unit
      decommissionedServices <- teamsAndRepositoriesConnector
                                  .getDecommissionedServices()
                                  .map(_.map(_.name))
      _                      <- deploymentRepository.clearFlags(SlugInfoFlag.values.toList, decommissionedServices.toList)

      missingLatestFlag      =  slugNames.intersect(activeRepos).diff(decommissionedServices).diff(latestServices)
      _                      <-
                                if missingLatestFlag.nonEmpty then
                                  logger.warn(s"The following services are missing Latest flag - and will be added: ${missingLatestFlag.mkString(",")}")
                                  missingLatestFlag.foldLeftM(()): (_, serviceName) =>
                                    for
                                      optVersion <- slugVersionRepository.getMaxVersion(serviceName)
                                      _          <- optVersion match
                                                      case Some(version) => deploymentRepository.setFlag(SlugInfoFlag.Latest, serviceName, version)
                                                      case None          => logger.warn(s"No max version found for $serviceName"); Future.unit
                                    yield ()
                                else Future.unit
    yield ()

  def updateDerivedViews(repoName: String)(using hc: HeaderCarrier): Future[Unit] =
    for
      oActiveRepo <- teamsAndRepositoriesConnector.getRepository(repoName).map(_.filterNot(_.isArchived).toSeq)
      oLatestMeta <- metaArtefactRepository.find(repoName)
      deployments <- deploymentRepository.findDeployed(Some(repoName))
      _           <- updateDerivedDependencyViews(oActiveRepo.toSeq, oLatestMeta.toSeq, deployments)
      _           =  logger.info(s"Running DerivedModuleRepository.update")
      _           <- oLatestMeta.fold(Future.unit)(meta => derivedModuleRepository.update(meta))
      _           =  logger.info(s"Finished running DerivedModuleRepository.update")
    yield ()

  def updateDerivedViewsForAllRepos()(using hc: HeaderCarrier): Future[Unit] =
    for
      activeRepos <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false))
      latestMeta  <- metaArtefactRepository.findLatest()
      deployments <- deploymentRepository.findDeployed()
      _           <- updateDerivedDependencyViews(activeRepos, latestMeta, deployments)
      _           =  logger.info(s"Running DerivedModuleRepository.populateAll")
      _           <- derivedModuleRepository
                       .populateAll()
                       .recover { e => logger.error("Failed to update DerivedModuleRepository", e) }
      _           =  logger.info(s"Finished running DerivedModuleRepository.populateAll")
      _           =  logger.info(s"Running DerivedGroupArtefactRepository.populateAll")
      _           <- derivedGroupArtefactRepository
                       .populateAll()
                       .recover { e => logger.error("Failed to update DerivedGroupArtefactRepository", e) }
      _           =  logger.info(s"Finished running DerivedGroupArtefactRepository.populateAll")
    yield ()

  private def updateDerivedDependencyViews(
    activeRepos: Seq[TeamsAndRepositoriesConnector.Repository],
    latestMeta : Seq[MetaArtefact],
    deployments: Seq[Deployment]
  ): Future[Unit] =
    for
      _ <- Future.unit
      _ =  logger.info(s"Running DerivedLatestDependencyRepository changes")
      _ <- latestMeta
             .flatMap: m =>
               activeRepos.find(_.name == m.name).map(r => (m, r.repoType))
             .foldLeftM(()):
               case (_, (meta, repoType)) =>
                 for
                   ds <- derivedLatestDependencyRepository.find(repoName = Some(meta.name), repoVersion = Some(meta.version))
                   _  <-
                         if ds.isEmpty || repoType == RepoType.Test then
                           val deps = toDependencies(meta, repoType)
                           logger.info(s"DerivedLatestDependencyRepository repoName: ${meta.name}, repoVersion: ${meta.version} - storing ${deps.size} dependencies")
                           derivedLatestDependencyRepository.update(meta.name, deps)
                         else
                           Future.unit
                 yield ()
      _ <- latestMeta
             .filterNot(m => activeRepos.exists(_.name == m.name))
             .foldLeftM(()): (_, meta) =>
               derivedLatestDependencyRepository.delete(meta.name)
      _ =  logger.info(s"Finished running DerivedLatestDependencyRepository changes")
      _ =  logger.info(s"Running DerivedDeployedDependencyRepository changes")
      _ <- deployments
             .groupBy(_.slugName)
             .toSeq
             .foldLeftM(()):
                case (_, (slugName, slugDeployments)) =>
                  derivedDeployedDependencyRepository.delete(slugName, ignoreVersions = slugDeployments.map(_.slugVersion))
      _ <- deployments
             .filter(d => activeRepos.exists(_.name == d.slugName))
             .flatMap: d =>
                d.flags.filterNot(_ == SlugInfoFlag.Latest).map(f => (d.slugName, d.slugVersion)) // Get all deployed versions
             .distinct
             .foldLeftM(()):
               case (_, (slugName, slugVersion)) =>
                 for
                   ds <- derivedDeployedDependencyRepository.find(slugName = slugName, slugVersion = slugVersion)
                   ms <- metaArtefactRepository.find(repositoryName = slugName, version = slugVersion)
                   _  <- (ds.isEmpty, ms.headOption) match
                           case (true, Some(meta)) => val deps = toDependencies(meta, RepoType.Service)
                                                      logger.info(s"DerivedDeployedDependencyRepository repoName: ${meta.name}, repoVersion: ${meta.version} - storing ${deps.size} dependencies")
                                                      derivedDeployedDependencyRepository.update(meta.name, meta.version, deps)
                           case __                 => Future.unit
                 yield ()
      _ <- deployments
             .filterNot(d => activeRepos.exists(_.name == d.slugName))
             .foldLeftM(()): (_, deployment) =>
               derivedDeployedDependencyRepository.delete(deployment.slugName)
      _ =  logger.info(s"Finished running DerivedDeployedDependencyRepository changes")
    yield ()

  private def toDependencies(meta: MetaArtefact, repoType: RepoType): List[MetaArtefactDependency] =
    DependencyGraphParser
      .parseMetaArtefact(meta)
      .groupBy { case (node, _) => node.group + node.artefact }                             // Unique index does not consider Scala version
      .flatMap { case (_, xs) => xs.headOption.map(x => (x._1 -> xs.flatMap(_._2).toSet)) } // Merge scopes with the same node minus scala version
      .map { case (node, scopes) => MetaArtefactDependency.apply(meta, repoType, node, scopes) }
      .toList
