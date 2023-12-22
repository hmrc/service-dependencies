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

import java.time.{LocalDate, Instant}
import org.apache.pekko.Done
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import play.api.cache.AsyncCacheApi
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig
import uk.gov.hmrc.servicedependencies.config.model.{CuratedDependencyConfig, DependencyConfig}
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependency, DependencyBobbyRule, ImportedBy}
import uk.gov.hmrc.servicedependencies.model.DependencyScope.Compile
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.LatestVersionRepository
import uk.gov.hmrc.servicedependencies.service.DependencyGraphParser.{Arrow, DependencyGraph, Node}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

class SlugDependenciesServiceSpec extends AnyFreeSpec with MockitoSugar with Matchers with ScalaFutures with OptionValues {

  import SlugDependenciesServiceSpec._

  private trait Fixture {
    val mockSlugInfoService           = mock[SlugInfoService]
    val mockServiceDependenciesConfig = mock[ServiceDependenciesConfig]
    val mockLatestVersionRepository   = mock[LatestVersionRepository]
    val mockServiceConfigsConnector   = mock[ServiceConfigsConnector]
    val mockGraphParser               = mock[DependencyGraphParser]

    val mockCache = new AsyncCacheApi {
      override def set(key: String, value: Any, expiration: Duration): Future[Done] = Future.successful(Done)
      override def remove(key: String): Future[Done] = Future.successful(Done)
      override def getOrElseUpdate[A](key: String, expiration: Duration)(orElse: => Future[A])(implicit evidence$1: ClassTag[A]): Future[A] = orElse
      override def get[T](key: String)(implicit evidence$2: ClassTag[T]): Future[Option[T]] = Future.successful(None)
      override def removeAll(): Future[Done] = Future.successful(Done)
    }

    val underTest =
      new SlugDependenciesService(
          mockServiceDependenciesConfig
        , mockGraphParser
        )

    def stubCuratedLibrariesOf(libraryNames: DependencyConfig*): Unit =
      when(mockServiceDependenciesConfig.curatedDependencyConfig)
        .thenReturn(aCuratedDependencyConfig(libraryNames.toList))

    // def stubLatestLibraryVersionLookupSuccessfullyReturns(versionsByName: Seq[(SlugDependency, Version)]): Unit =
    //   when(mockLatestVersionRepository.getAllEntries())
    //     .thenReturn(
    //       Future.successful(
    //         versionsByName.map { case (sd, v) =>
    //           LatestVersion(name = sd.artifact, group = sd.group, version = v)
    //         }
    //       )
    //     )

    def stubBobbyRulesViolations(bobbyRules: Map[(String, String), List[DependencyBobbyRule]]): Unit = {
      val bobbyRules2 = BobbyRules(
        bobbyRules.map { case ((g, a), rs) =>
          ((g, a), rs.map(r => BobbyRule(organisation = g, name = a, range = r.range, reason = r.reason, from = r.from, exemptProjects = r.exemptProjects)))
        }
      )
      when(mockServiceConfigsConnector.getBobbyRules())
        .thenReturn(Future.successful(bobbyRules2))
    }


    def stubNoEnrichmentsForDependencies(): Unit = {
      // stubLatestLibraryVersionLookupSuccessfullyReturns(Seq.empty)
      stubBobbyRulesViolations(Map.empty)
    }

    def stubSlugVersionIsUnrecognised(name: String, version: Version): Unit =
      when(mockSlugInfoService.getSlugInfo(name, version))
        .thenReturn(Future.successful(None))
  }
}

private object SlugDependenciesServiceSpec {
  val SlugName = "a-slug-name"
  val SlugVersion = Version(major = 1, minor = 2, patch = 3)

  def slugInfo(withName: String, withVersion: Version): SlugInfo =
    SlugInfo(
      uri               = "some-uri",
      created           = Instant.now(),
      name              = withName,
      version           = withVersion,
      teams             = Nil,
      runnerVersion     = "some-runner-version",
      classpath         = "some-classpath",
      java              = JavaInfo("some-java-version", "some-java-vendor", "some-java-kind"),
      sbtVersion        = Some("1.4.9"),
      repoUrl           = Some("https://github.com/hmrc/test.git"),
      applicationConfig = "some-application-config",
      slugConfig        = "some-slug-config"
    )

  def aCuratedDependencyConfig(withLibraries: List[DependencyConfig]) =
    CuratedDependencyConfig(
      sbtPlugins = List.empty
    , libraries  = withLibraries
    , others     = List.empty
    )
}
