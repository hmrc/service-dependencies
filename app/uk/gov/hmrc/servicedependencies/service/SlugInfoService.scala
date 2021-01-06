/*
 * Copyright 2021 HM Revenue & Customs
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
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedGroupArtefactRepository, DerivedServiceDependenciesRepository}
import uk.gov.hmrc.servicedependencies.persistence.{JdkVersionRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository            : SlugInfoRepository,
  serviceDependencyRepository   : DerivedServiceDependenciesRepository,
  jdkVersionRepository          : JdkVersionRepository,
  groupArtefactRepository       : DerivedGroupArtefactRepository,
  teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector,
  releasesApiConnector          : ReleasesApiConnector,
  githubRawConnector             : GithubRawConnector,
)(implicit ec: ExecutionContext
) extends Logging {
  def addSlugInfo(slug: SlugInfo): Future[Boolean] =
    for {
      // Determine which slug is latest from the existing collection, not relying on the potentially stale state of the message
      added    <- slugInfoRepository.add(slug.copy(latest = false))
      isLatest <- slugInfoRepository.getSlugInfos(name = slug.name, optVersion = None)
                    .map {
                      case Nil      => true
                      case nonempty => val isLatest = nonempty.map(_.version).max == slug.version
                                       logger.info(s"Slug ${slug.name} ${slug.version} isLatest=$isLatest (out of: ${nonempty.map(_.version).sorted})")
                                       isLatest
                    }
      _        <- if (isLatest) slugInfoRepository.markLatest(slug.name, slug.version) else Future(())
    } yield added

  def getSlugInfos(name: String, version: Option[String]): Future[Seq[SlugInfo]] =
    slugInfoRepository.getSlugInfos(name, version)

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(name, flag)

  def getSlugInfo(name: String, version: String): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfos(name, Some(version)).map(_.headOption)

  def findServicesWithDependency(
      flag        : SlugInfoFlag
    , group       : String
    , artefact    : String
    , versionRange: BobbyVersionRange
    )(implicit hc: HeaderCarrier): Future[Seq[ServiceDependency]] =
      for {
        services            <- serviceDependencyRepository.findServicesWithDependency(flag, group, artefact)
        servicesWithinRange =  services.filter(_.depSemanticVersion.map(versionRange.includes).getOrElse(true)) // include invalid semanticVersion in results
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
      inactiveServices       =  serviceNames.diff(activeRepos).toList
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
                                    case (flag, None         ) => slugInfoRepository.clearFlag(flag, serviceName)
                                    case (flag, Some(version)) => slugInfoRepository.setFlag(flag, serviceName, version)
                                  }
                                }
      _                      <- slugInfoRepository.clearFlags(SlugInfoFlag.values, decomissionedServices)
      _                      <- // we have found some "archived" projects which are still deployed, we will only remove the latest flag for them
                                slugInfoRepository.clearFlags(List(SlugInfoFlag.Latest), inactiveServices)
    } yield ()
  }

  def findJDKVersions(flag: SlugInfoFlag): Future[Seq[JDKVersion]] =
    jdkVersionRepository.findJDKUsage(flag)
}
