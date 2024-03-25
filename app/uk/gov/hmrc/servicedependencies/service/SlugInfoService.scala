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

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, JdkVersionRepository, SbtVersionRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedDeployedDependencyRepository, DerivedGroupArtefactRepository, DerivedLatestDependencyRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository                 : SlugInfoRepository,
  slugVersionRepository              : SlugVersionRepository,
  jdkVersionRepository               : JdkVersionRepository,
  sbtVersionRepository               : SbtVersionRepository,
  deploymentRepository               : DeploymentRepository,
  teamsAndRepositoriesConnector      : TeamsAndRepositoriesConnector,
  derivedDeployedDependencyRepository: DerivedDeployedDependencyRepository,
  derivedLatestDependencyRepository  : DerivedLatestDependencyRepository,
  derivedGroupArtefactRepository     : DerivedGroupArtefactRepository,
)(implicit ec: ExecutionContext
) extends Logging {

  def addSlugInfo(slug: SlugInfo): Future[Unit] =
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
    } yield ()

  def deleteSlugInfo(name: String, version: Version): Future[Unit] =
    for {
      _ <- deploymentRepository.delete(name, version)
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
    )(implicit hc: HeaderCarrier): Future[Seq[MetaArtefactDependency]] =
      for {
        allTeams <- teamsAndRepositoriesConnector.getTeamsForServices()
        services <- flag match {
                      case SlugInfoFlag.Latest => derivedLatestDependencyRepository.find  (group = Some(group), artefact = Some(artefact), scopes = scopes, repoType = Some(List(RepoType.Service)))
                      case _                   => derivedDeployedDependencyRepository.find(group = Some(group), artefact = Some(artefact), scopes = scopes, flag     = flag)
                    }
        results  =  services
                      .filter(s => versionRange.includes(s.depVersion))
                      .map(r => r.copy(teams = allTeams.getTeams(r.repoName).toList.sorted))
      } yield results

  def findGroupsArtefacts(): Future[Seq[GroupArtefacts]] =
    derivedGroupArtefactRepository.findGroupsArtefacts()

  def findJDKVersions(teamName: Option[String], flag: SlugInfoFlag)(implicit hc: HeaderCarrier): Future[Seq[JDKVersion]] =
    teamName match {
      case Some(n) => for {
                        team <- teamsAndRepositoriesConnector.getTeam(n)
                        xs   <- jdkVersionRepository.findJDKUsage(flag)
                      } yield xs.filter(x => team.services.contains(x.name))
      case None    => jdkVersionRepository.findJDKUsage(flag)
    }

  def findSBTVersions(teamName: Option[String], flag: SlugInfoFlag)(implicit hc: HeaderCarrier): Future[Seq[SBTVersion]] =
    teamName match {
      case Some(n) => for {
                        team <- teamsAndRepositoriesConnector.getTeam(n)
                        xs   <- sbtVersionRepository.findSBTUsage(flag)
                      } yield xs.filter(x => team.services.contains(x.serviceName))
      case None    => sbtVersionRepository.findSBTUsage(flag)
    }
}
