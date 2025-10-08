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

import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{DeploymentRepository, JdkVersionRepository, SbtVersionRepository, SlugInfoRepository, SlugVersionRepository}
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedGroupArtefactRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository            : SlugInfoRepository,
  slugVersionRepository         : SlugVersionRepository,
  jdkVersionRepository          : JdkVersionRepository,
  sbtVersionRepository          : SbtVersionRepository,
  deploymentRepository          : DeploymentRepository,
  teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector,
  derivedGroupArtefactRepository: DerivedGroupArtefactRepository,
)(using ec: ExecutionContext
) extends Logging:

  def addSlugInfo(slug: SlugInfo): Future[Unit] =
    for
      // Determine which slug is latest from the existing collection
      _        <- slugInfoRepository.add(slug)
      isLatest <- slugVersionRepository.getMaxVersion(name = slug.name)
                    .map:
                      case None             => true
                      case Some(maxVersion) => val isLatest = maxVersion <= slug.version
                                               logger.info(s"Slug ${slug.name} ${slug.version} isLatest=$isLatest (latest is: ${maxVersion})")
                                               isLatest
      _        <- if isLatest then deploymentRepository.markLatest(slug.name, slug.version) else Future.unit
    yield ()

  def deleteSlugInfo(name: String, version: Version): Future[Unit] =
    for
      _ <- deploymentRepository.delete(name, version)
      _ <- slugInfoRepository.delete(name, version)
    yield ()

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(name, flag)

  def getSlugInfo(name: String, version: Version): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(name, version)

  def findGroupsArtefacts(): Future[Seq[GroupArtefacts]] =
    derivedGroupArtefactRepository.findGroupsArtefacts()

  def findJDKVersions(teamName: Option[String], digitalService: Option[String], flag: SlugInfoFlag)(using hc: HeaderCarrier): Future[Seq[JDKVersion]] =
    (teamName, digitalService) match
      case (None, None) => jdkVersionRepository.findJDKUsage(flag)
      case _            =>
                           for
                             repos <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false), teamName = teamName, digitalService = digitalService)
                             xs    <- jdkVersionRepository.findJDKUsage(flag)
                           yield xs.filter(x => repos.exists(_.name == x.name))

  def findSBTVersions(teamName: Option[String], digitalService: Option[String], flag: SlugInfoFlag)(using hc: HeaderCarrier): Future[Seq[SBTVersion]] =
    (teamName, digitalService) match
      case (None, None) => sbtVersionRepository.findSBTUsage(flag) // for Latest, we could get the data from MetaArtefact to support non-services
      case _            =>
                           for
                             repos <- teamsAndRepositoriesConnector.getAllRepositories(archived = Some(false), teamName = teamName, digitalService = digitalService)
                             xs    <- sbtVersionRepository.findSBTUsage(flag)
                           yield xs.filter(x => repos.exists(_.name == x.serviceName))
