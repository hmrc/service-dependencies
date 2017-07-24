/*
 * Copyright 2017 HM Revenue & Customs
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

//package uk.gov.hmrc.servicedependencies
//
//import org.mockito.ArgumentMatchers.any
//import org.mockito.Mockito
//import org.mockito.Mockito.when
//import org.scalatest.concurrent.ScalaFutures
//import org.scalatest.mock.MockitoSugar
//import org.scalatest.{BeforeAndAfterEach, FreeSpec, FunSpec, Matchers}
//import org.scalatestplus.play.OneServerPerSuite
//import play.api.inject.guice.GuiceApplicationBuilder
//import play.api.test.FakeRequest
//import reactivemongo.api.DB
//import uk.gov.hmrc.githubclient.{ExtendedContentsService, GithubApiClient}
//import uk.gov.hmrc.servicedependencies.model.{MongoLibraryVersion, RepositoryLibraryDependencies}
//import uk.gov.hmrc.servicedependencies.presistence.MongoLock
//import uk.gov.hmrc.servicedependencies.service._
//
//import scala.concurrent.{ExecutionContext, Future}
//
//class ServiceDependenciesControllerSpec extends FreeSpec with BeforeAndAfterEach with OneServerPerSuite with Matchers with MockitoSugar with ScalaFutures {
//
//  implicit override lazy val app = new GuiceApplicationBuilder().configure (
//
//    "play.ws.ssl.loose.acceptAnyCertificate" -> true,
//    "play.http.requestHandler" -> "play.api.http.DefaultHttpRequestHandler").build()
//
//
//  "a" - {
//    "should b" in new Setup {
//      override def func: () => Future[Seq[RepositoryLibraryDependencies]] = TestServiceDependenciesImpl.repositoryLibraryDependencyDataLoaderUpdater
//
//      TestServiceDependenciesImpl.reloadLibraryDependenciesForAllRepositories().apply(FakeRequest())
//
//    }
//
//  }
//
//  trait Setup {
//    val mockedGithubEnterpriseClient = mock[GithubApiClient]
//    val mockedGithubOpenClient = mock[GithubApiClient]
//
//    def func: () => Future[Seq[RepositoryLibraryDependencies]] = { () =>
//      println("Doing things......")
//      Future.successful(Seq(RepositoryLibraryDependencies("repoX", Nil)))
//    }//mock[() => Future[Seq[RepositoryLibraryDependencies]]]
//
//    object TestMongoLock extends MongoLock(mock[() => DB], "") {
//      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
//        body.map(Some(_))
//      }
//    }
//
//    val stringBuilder: String => MongoLock = _ => TestMongoLock
//
//    val libraryName = "lib-verbs"
//    object TestServiceDependenciesImpl extends ServiceDependenciesController {
//      override lazy protected val dataSource: CachingDependenciesDataSource = ???
//
//      override def libraryDependencyDataUpdatingService: LibraryDependencyDataUpdatingService =
//        new DefaultLibraryDependencyDataUpdatingService(mock[() => Future[Seq[MongoLibraryVersion]]], repositoryLibraryDependencyDataLoaderUpdater, stringBuilder) {
//
//        }
//
//      override def repositoryDependencyService: RepositoryDependencyService = ???
//
//
//      override lazy protected val curatedLibraries: List[String] = List(libraryName)
//      override val githubOpen = mock[GithubOpen]
//      override val githubEnterprise = mock[GithubEnterprise]
//      override lazy val dependenciesDataSource = mock[DependenciesDataSource]
//      override lazy protected val gitEnterpriseClient: GithubApiClient = mockedGithubEnterpriseClient
//      override lazy protected val gitOpenClient: GithubApiClient = mockedGithubOpenClient
//      override lazy protected val releasesConnector: DeploymentsDataSource = ???
//      override lazy protected val teamsAndRepositoriesClient: TeamsAndRepositoriesClient = ???
//      override lazy protected val config: ServiceDependenciesConfig = mock[ServiceDependenciesConfig]
//    }
//
//    val mockedGithubOpen = TestServiceDependenciesImpl.githubOpen
//    val mockedGithubEnterprise = TestServiceDependenciesImpl.githubEnterprise
//
//
//  }
//}
