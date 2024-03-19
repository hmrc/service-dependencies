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
import uk.gov.hmrc.servicedependencies.persistence.derived.{DerivedGroupArtefactRepository, DerivedServiceDependenciesRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository                 : SlugInfoRepository,
  slugVersionRepository              : SlugVersionRepository,
  jdkVersionRepository               : JdkVersionRepository,
  sbtVersionRepository               : SbtVersionRepository,
  deploymentRepository               : DeploymentRepository,
  teamsAndRepositoriesConnector      : TeamsAndRepositoriesConnector,
  derivedServiceDependencyRepository : DerivedServiceDependenciesRepository,
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
      _ <- derivedServiceDependencyRepository.delete(name, version)
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
        services            <- derivedServiceDependencyRepository.findServicesWithDependency(flag, group, artefact, scopes)
        servicesWithinRange =  services.filter(s => versionRange.includes(s.depVersion))
        teamsForServices    <- teamsAndRepositoriesConnector.getTeamsForServices
      } yield servicesWithinRange.map { r =>
        r.copy(teams = teamsForServices.getTeams(r.slugName).toList.sorted)
      }

  def findGroupsArtefacts(): Future[Seq[GroupArtefacts]] =
    derivedGroupArtefactRepository.findGroupsArtefacts()

  def findJDKVersions(teamName: Option[String], flag: SlugInfoFlag)(implicit hc: HeaderCarrier): Future[Seq[JDKVersion]] =
    for {
      allJdkUsages <- jdkVersionRepository.findJDKUsage(flag)
      jdkUsages    <- teamName match {
                        case Some(teamName) => teamsAndRepositoriesConnector.getTeam(teamName)
                                                 .map(team => allJdkUsages.filter(jdkUsage => team.services.contains(jdkUsage.repoName)))
                        case None           => Future.successful(allJdkUsages)
                      }
    } yield jdkUsages

  def findSBTVersions(teamName: Option[String], flag: SlugInfoFlag)(implicit hc: HeaderCarrier): Future[Seq[SBTVersion]] =
    for {
      allSbtUsages <- sbtVersionRepository.findSBTUsage(flag) // for Latest, we could get the data from MetaArtefact to support non-services
      sbtUsages    <- teamName match {
                        case Some(teamName) => teamsAndRepositoriesConnector.getTeam(teamName)
                                                 .map(team => allSbtUsages.filter(sbtUsage => team.allRepos.contains(sbtUsage.repoName)))
                        case None           => Future.successful(allSbtUsages)
                      }
    } yield sbtUsages
}
