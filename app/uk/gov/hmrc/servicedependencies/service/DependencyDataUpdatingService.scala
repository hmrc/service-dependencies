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

import java.time.Instant

import cats.implicits._
import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.DependencyConfig
import uk.gov.hmrc.servicedependencies.connector.{ArtifactoryConnector, ServiceConfigsConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.model.LatestVersion
import uk.gov.hmrc.servicedependencies.persistence.LatestVersionRepository
import uk.gov.hmrc.servicedependencies.persistence.derived.DerivedGroupArtefactRepository
import uk.gov.hmrc.servicedependencies.util.Max

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependencyDataUpdatingService @Inject()(
  serviceDependenciesConfig     : ServiceDependenciesConfig
, latestVersionRepository       : LatestVersionRepository
, derivedGroupArtefactRepository: DerivedGroupArtefactRepository
, teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
, artifactoryConnector          : ArtifactoryConnector
, serviceConfigsConnector       : ServiceConfigsConnector
)(implicit ec: ExecutionContext
) extends Logging {

  def now: Instant = Instant.now()

  lazy val curatedDependencyConfig =
    serviceDependenciesConfig.curatedDependencyConfig

  private[service] def versionsToUpdate(): Future[List[DependencyConfig]] =
    for {
      hmrcDependencies                  <- hmrcDependencies()
      nonHmrcDependenciesWithBobbyRules <- nonHmrcDependenciesWithBobbyRules()
    } yield
      ((hmrcDependencies ++ nonHmrcDependenciesWithBobbyRules).groupBy(a => a.group + ":" + a.name) ++
        curatedDependencyConfig.allDependencies               .groupBy(a => a.group + ":" + a.name)
      ).values.flatten.toList

  def reloadLatestVersions(): Future[List[LatestVersion]] =
    versionsToUpdate()
      .flatMap(
        _.foldLeftM[Future, List[LatestVersion]](List.empty) {
          case (acc, config) =>
            (for {
               optVersion   <- config.latestVersion
                                 .fold(
                                   artifactoryConnector
                                     .findLatestVersion(config.group, config.name)
                                     .map(vs => Max.maxOf(vs.values))
                                 )(v => Future.successful(Some(v)))
               optDbVersion <- optVersion.traverse { version =>
                                 val dbVersion =
                                   LatestVersion(name = config.name, group = config.group, version = version, now)
                                 latestVersionRepository
                                   .update(dbVersion)
                                   .map(_ => dbVersion)
                               }
             } yield optDbVersion
            ).map(acc ++ _)
        }
      )

  private def hmrcDependencies(): Future[Seq[DependencyConfig]] =
    derivedGroupArtefactRepository.findGroupsArtefacts
      .map(groupsArtefacts =>
        groupsArtefacts
          .filter(_.group.startsWith("uk.gov.hmrc"))
          .flatMap(hmrcGA =>
            hmrcGA.artefacts.map(artefact =>
              DependencyConfig(name = artefact, group = hmrcGA.group, latestVersion = None)
            )
          )
      )

  private def nonHmrcDependenciesWithBobbyRules(): Future[Seq[DependencyConfig]] =
    serviceConfigsConnector.getBobbyRules
      .map(bobbyRules =>
        bobbyRules
          .asMap
          .filterNot(_._1._1.startsWith("uk.gov.hmrc"))
          .map(_._1)
          .map(bobbyRuleKey =>
            DependencyConfig(name = bobbyRuleKey._2, group = bobbyRuleKey._1, latestVersion = None)
          ).toSeq
      )
}
