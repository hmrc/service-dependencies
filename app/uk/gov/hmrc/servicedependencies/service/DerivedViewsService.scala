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
import uk.gov.hmrc.servicedependencies.connector.{ReleasesApiConnector, TeamsAndRepositoriesConnector, ServiceConfigsConnector}
import uk.gov.hmrc.servicedependencies.model.{BobbyReport, BobbyRule, MetaArtefactDependency, RepoType, SlugInfoFlag, Version}
import uk.gov.hmrc.servicedependencies.persistence.{Deployment, DeploymentRepository, MetaArtefactRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedBobbyReportRepository, DerivedDeployedDependencyRepository, DerivedGroupArtefactRepository, DerivedLatestDependencyRepository, DerivedModuleRepository}
import uk.gov.hmrc.servicedependencies.util.DependencyGraphParser

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicedependencies.model.MetaArtefact

@Singleton
class DerivedViewsService @Inject()(
  teamsAndRepositoriesConnector      : TeamsAndRepositoriesConnector
, serviceConfigsConnector            : ServiceConfigsConnector
, releasesApiConnector               : ReleasesApiConnector
, metaArtefactRepository             : MetaArtefactRepository
, slugInfoRepository                 : SlugInfoRepository
, slugVersionRepository              : SlugVersionRepository
, deploymentRepository               : DeploymentRepository
, derivedGroupArtefactRepository     : DerivedGroupArtefactRepository
, derivedModuleRepository            : DerivedModuleRepository
, derivedDeployedDependencyRepository: DerivedDeployedDependencyRepository
, derivedLatestDependencyRepository  : DerivedLatestDependencyRepository
, derivedBobbyReportRepository       : DerivedBobbyReportRepository
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
                                  .getDecommissionedRepositories(Some(RepoType.Service))
                                  .map(_.map(_.name))
      _                      <- deploymentRepository.clearFlags(SlugInfoFlag.values.toList, decommissionedServices.toList)

      allActiveServices      =  slugNames.intersect(activeRepos).diff(decommissionedServices)
      _                      <- allActiveServices.foldLeftM(()): (_, serviceName) =>
                                  for
                                    optVersion <- slugVersionRepository.getMaxVersion(serviceName)
                                    _          <- optVersion match
                                                    case Some(version) => deploymentRepository.setFlag(SlugInfoFlag.Latest, serviceName, version)
                                                    case None          => logger.warn(s"No max version found for $serviceName"); Future.unit
                                  yield ()
    yield ()

  def updateDerivedViews(repoName: String)(using hc: HeaderCarrier): Future[Unit] =
    for
      oActiveRepo     <- teamsAndRepositoriesConnector.getRepository(repoName).map(_.filterNot(_.isArchived))
      oDecommissioned <- teamsAndRepositoriesConnector.getDecommissionedRepositories().map(_.find(_.name == repoName))
      oLatestMeta     <- metaArtefactRepository.find(repoName)
      oLatestMetaTup  =  oLatestMeta.map(meta => (meta.name, meta.version))
      deployments     <- deploymentRepository.findDeployed(Some(repoName))
      _               <- updateDerivedDependencyViews(oActiveRepo.toSeq, oDecommissioned.toSeq, oLatestMetaTup.toSeq, deployments)
      bobbyRules      <- serviceConfigsConnector.getBobbyRules().map(_.asMap.values.flatten.toSeq)
      _               <- updateRepoBobbyRules(bobbyRules, oActiveRepo.toSeq, oDecommissioned.toSeq, oLatestMetaTup.toSeq, deployments)
      _               =  logger.info(s"Running DerivedModuleRepository.update")
      _               <- oLatestMeta.fold(Future.unit)(meta => derivedModuleRepository.update(meta))
      _               =  logger.info(s"Finished running DerivedModuleRepository.update")
    yield ()

  def updateDerivedViewsForAllRepos()(using hc: HeaderCarrier): Future[Unit] =
    for
      activeRepos    <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false))
      decommissioned <- teamsAndRepositoriesConnector.getDecommissionedRepositories()
      latestMeta     <- metaArtefactRepository.findLatest()
      deployments    <- deploymentRepository.findDeployed()
      _              <- updateDerivedDependencyViews(activeRepos, decommissioned, latestMeta, deployments)
      bobbyRules     <- serviceConfigsConnector.getBobbyRules().map(_.asMap.values.flatten.toSeq)
      _              <- updateRepoBobbyRules(bobbyRules, activeRepos, decommissioned, latestMeta, deployments)
      _              =  logger.info(s"Running DerivedModuleRepository.populateAll")
      _              <- derivedModuleRepository
                          .populateAll()
                          .recover { e => logger.error("Failed to update DerivedModuleRepository", e) }
      _              =  logger.info(s"Finished running DerivedModuleRepository.populateAll")
      _              =  logger.info(s"Running DerivedGroupArtefactRepository.populateAll")
      _              <- derivedGroupArtefactRepository
                          .populateAll()
                          .recover { e => logger.error("Failed to update DerivedGroupArtefactRepository", e) }
      _              =  logger.info(s"Finished running DerivedGroupArtefactRepository.populateAll")
    yield ()

  private def updateDerivedDependencyViews(
    activeRepos   : Seq[TeamsAndRepositoriesConnector.Repository],
    decommissioned: Seq[TeamsAndRepositoriesConnector.DecommissionedRepository],
    latestMeta    : Seq[(String, Version)],
    deployments   : Seq[Deployment]
  ): Future[Unit] =
    for
      _ <- Future.unit
      _ =  logger.info(s"Running DerivedLatestDependencyRepository changes")
      latestMetaSet = latestMeta.map(_._1).toSet
      // Process repositories from latestMeta (existing logic)
      _ <- latestMeta
             .flatMap: (name, version) =>
               activeRepos.find(_.name == name).map(r => (name, version, r.repoType))
             .foldLeftM(()):
               case (_, (name, version, repoType)) =>
                 for
                   ds <- derivedLatestDependencyRepository.find(repoName = Some(name), repoVersion = Some(version))
                   _  <-
                         if ds.isEmpty || repoType == RepoType.Test then
                           metaArtefactRepository
                             .find(repositoryName = name, version = version)
                             .flatMap:
                              case None       =>
                                Future.unit
                              case Some(meta) =>
                                val deps = toDependencies(meta, repoType)
                                logger.info(s"DerivedLatestDependencyRepository repoName: ${meta.name}, repoVersion: ${meta.version} - storing ${deps.size} dependencies")
                                derivedLatestDependencyRepository.update(meta.name, deps)
                         else
                           Future.unit
                 yield ()
      // Process test repositories that are not in latestMeta
      _ <- activeRepos
             .filter: repo =>
               repo.repoType == RepoType.Test && !latestMetaSet.contains(repo.name)
             .foldLeftM(()):
               case (_, repo) =>
                 for
                   optMeta <- metaArtefactRepository.find(repo.name)
                   meta    <- optMeta match
                               case Some(m) => Future.successful(Some(m))
                               case None    =>
                                 // If no latest: true flag, try to find all versions and get the latest one
                                 for
                                   allVersions <- metaArtefactRepository.findAllVersions(repo.name)
                                   result      <-
                                         if allVersions.nonEmpty then
                                           val latest = allVersions.maxBy(_.version)
                                           Future.successful(Some(latest))
                                         else
                                           Future.successful(None)
                                 yield result
                   _       <- meta match
                               case None       =>
                                 logger.debug(s"Skipping test repository ${repo.name} - no meta artefact found")
                                 Future.unit
                               case Some(meta) =>
                                 processTestRepository(repo, meta)
                 yield ()
      _ <- derivedLatestDependencyRepository.deleteMany(decommissioned)
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
                   _  <- (ds.isEmpty, ms) match
                           case (true, Some(meta)) => val deps = toDependencies(meta, RepoType.Service)
                                                      logger.info(s"DerivedDeployedDependencyRepository repoName: ${meta.name}, repoVersion: ${meta.version} - storing ${deps.size} dependencies")
                                                      derivedDeployedDependencyRepository.update(meta.name, meta.version, deps)
                           case __                 => Future.unit
                 yield ()
      _ <- derivedDeployedDependencyRepository.deleteMany(decommissioned)
      _ =  logger.info(s"Finished running DerivedDeployedDependencyRepository changes")
    yield ()

  private def processTestRepository(repo: TeamsAndRepositoriesConnector.Repository, meta: MetaArtefact): Future[Unit] =
    for
      ds <- derivedLatestDependencyRepository.find(repoName = Some(repo.name), repoVersion = Some(meta.version))
      _  <-
            if ds.isEmpty then
              val deps = toDependencies(meta, repo.repoType)
              logger.info(s"DerivedLatestDependencyRepository repoName: ${meta.name}, repoVersion: ${meta.version} (test repo not in latestMeta) - storing ${deps.size} dependencies")
              derivedLatestDependencyRepository.update(meta.name, deps)
            else
              Future.unit
    yield ()

  private def toDependencies(meta: MetaArtefact, repoType: RepoType): List[MetaArtefactDependency] =
    DependencyGraphParser
      .parseMetaArtefact(meta)
      .groupBy { case (node, _) => node.group + node.artefact }                             // Unique index does not consider Scala version
      .flatMap { case (_, xs) => xs.headOption.map(x => x._1 -> xs.flatMap(_._2).toSet) } // Merge scopes with the same node minus scala version
      .map { case (node, scopes) => MetaArtefactDependency.apply(meta, repoType, node, scopes) }
      .toList

  private def updateRepoBobbyRules(
    bobbyRules    : Seq[BobbyRule],
    activeRepos   : Seq[TeamsAndRepositoriesConnector.Repository],
    decommissioned: Seq[TeamsAndRepositoriesConnector.DecommissionedRepository],
    latestMeta    : Seq[(String, Version)],
    deployments   : Seq[Deployment]
  ): Future[Unit] =
    // Get all repositories from latestMeta and deployments
    val reposFromLatestMetaAndDeployments = (latestMeta ++ deployments.map(d => (d.slugName, d.slugVersion)))
                                             .distinct
                                             .flatMap: (repoName, repoVersion) =>
                                               activeRepos.collect:
                                                 case x if x.name == repoName => (repoName, repoVersion, x.repoType)
    // Get test repositories that are in DERIVED-latest-dependencies but not in latestMeta
    // We query DERIVED-latest-dependencies directly to get test repos with their versions
    val latestMetaSet = latestMeta.map(_._1).toSet
    val testReposInActive = activeRepos.filter: repo =>
                               repo.repoType == RepoType.Test && !latestMetaSet.contains(repo.name)
    for
      testDeps <- derivedLatestDependencyRepository.find(
                    repoType = Some(Seq(RepoType.Test))
                  )
      // Extract unique (repoName, repoVersion) pairs from test dependencies
      testReposWithVersions = testDeps
                                .map(dep => (dep.repoName, dep.repoVersion))
                                .distinct
                                .flatMap: (repoName, repoVersion) =>
                                  testReposInActive.find(_.name == repoName).map(repo => (repoName, repoVersion, repo.repoType))
      // Combine all repositories to process
      allReposToProcess = (reposFromLatestMetaAndDeployments ++ testReposWithVersions).distinct
      _                 <- Future.successful(allReposToProcess)
                           .flatMap: allRepos =>
                             allRepos
                               .groupBy(_._1)
                               .toSeq
                               .foldLeftM(()):
                                 case (_, (repoName, groupedData)) =>
                                   groupedData
                                     .distinct
                                     .foldLeftM(Seq.empty[BobbyReport]):
                                       case (acc, (repoName, repoVersion, repoType)) =>
                                         for
                                           xs   <- derivedLatestDependencyRepository.find(repoName = Some(repoName), repoVersion = Some(repoVersion))
                                           deps <- if   xs.isEmpty
                                                   then derivedDeployedDependencyRepository.find(slugName = repoName, slugVersion = repoVersion)
                                                   else Future.successful(xs)
                                         yield
                                           acc :+ BobbyReport(
                                             repoName    = repoName
                                           , repoVersion = repoVersion
                                           , repoType    = repoType
                                           , violations  = bobbyRules
                                                             .flatMap: bobby =>
                                                               deps.collect:
                                                                 case d
                                                                   if bobby.organisation == d.depGroup
                                                                   && bobby.name         == d.depArtefact
                                                                   && bobby.range.includes(d.depVersion) =>
                                                                     BobbyReport.Violation(
                                                                       depGroup    = d.depGroup
                                                                     , depArtefact = d.depArtefact
                                                                     , depVersion  = d.depVersion
                                                                     , depScopes   = d.depScopes
                                                                     , range       = bobby.range
                                                                     , reason      = bobby.reason
                                                                     , from        = bobby.from
                                                                     , exempt      = bobby.exemptProjects.contains(d.repoName)
                                                                     )
                                           , lastUpdated  = java.time.Instant.now()
                                           , latest       = latestMeta .exists((n,v) => n          == repoName && v             == repoVersion)
                                           , production   = deployments.exists(d     => d.slugName == repoName && d.slugVersion == repoVersion && d.production)
                                           , qa           = deployments.exists(d     => d.slugName == repoName && d.slugVersion == repoVersion && d.qa)
                                           , staging      = deployments.exists(d     => d.slugName == repoName && d.slugVersion == repoVersion && d.staging)
                                           , development  = deployments.exists(d     => d.slugName == repoName && d.slugVersion == repoVersion && d.development)
                                           , externalTest = deployments.exists(d     => d.slugName == repoName && d.slugVersion == repoVersion && d.externalTest)
                                           , integration  = deployments.exists(d     => d.slugName == repoName && d.slugVersion == repoVersion && d.integration)
                                           )
                                     .flatMap: bobbyReports =>
                                       derivedBobbyReportRepository.update(repoName, bobbyReports)
      _ <- derivedBobbyReportRepository.deleteMany(decommissioned)
    yield ()
