package uk.gov.hmrc.servicedependencies.service

import java.time.Instant

import org.mockito.{ArgumentMatchers, ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{ServiceConfigsConnector, Team, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency}
import uk.gov.hmrc.servicedependencies.model.{BobbyRule, Version}
import uk.gov.hmrc.servicedependencies.persistence.SlugInfoRepository
import uk.gov.hmrc.servicedependencies.service.SlugDependenciesService.TargetVersion

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TeamDependencyServiceSpec extends AnyWordSpec with Matchers with MockitoSugar with ScalaFutures with ArgumentMatchersSugar {
  val teamsAndReposConnector  = mock[TeamsAndRepositoriesConnector]
  val slugInfoRepository      = mock[SlugInfoRepository]
  val githubDepLookup         = mock[DependencyDataUpdatingService]
  val serviceConfigsConnector = mock[ServiceConfigsConnector]
  val serviceConfigsService   = new ServiceConfigsService(serviceConfigsConnector)
  val slugDependenciesService = mock[SlugDependenciesService]
  val tds = new TeamDependencyService(teamsAndReposConnector, slugInfoRepository, githubDepLookup, serviceConfigsService, slugDependenciesService)

  "replaceServiceDeps" should {
    "replace library section with slug data" in {

      val lib1 = new Dependency("foolib", Version("1.2.3"), None, List.empty)
      val lib2 = new Dependency("foolib", Version("1.2.4"), None, List.empty)
      val dep = Dependencies("foo", libraryDependencies = Seq(lib1), Nil, Nil, Instant.now() )

      when(slugDependenciesService.curatedLibrariesOfSlug(dep.repositoryName, TargetVersion.Latest)).thenReturn(Future.successful(Option(List(lib2))))

      val res = tds.replaceServiceDeps(dep).futureValue

      res.libraryDependencies shouldBe Seq(lib2)
      res.sbtPluginsDependencies shouldBe Nil
      res.otherDependencies shouldBe Nil
    }

  }

  "findAllDepsForTeam" should {

    "return dependencies for all projects belonging to  team" in {
      implicit val hc: HeaderCarrier = new HeaderCarrier()
      val team = new Team("foo", Option(Map(
        "Service" -> Seq("foo-service"))))

      when(teamsAndReposConnector.getTeamDetails("foo")).thenReturn(Future.successful(team))

      val fooDep1    = Dependency("foo-dep1", Version("1.2.0"), None, List.empty)
      val fooDep2    = Dependency("foo-dep2", Version("0.6.0"), None, List.empty)
      val fooSlugDep = Dependency("foo-dep2", Version("7.7.7"), None, List.empty)

      val fooDependencies = Dependencies(
        "foo-service", libraryDependencies = Seq(fooDep1, fooDep2),
        sbtPluginsDependencies = Seq.empty,
        otherDependencies = Seq.empty,
        lastUpdated = Instant.now()
      )

      when(githubDepLookup.getDependencyVersionsForAllRepositories()).thenReturn(Future.successful(Seq(fooDependencies)))

      when(slugDependenciesService
        .curatedLibrariesOfSlug(ArgumentMatchers.eq("foo-service"), ArgumentMatchers.eq(TargetVersion.Latest)))
        .thenReturn(Future.successful(Option(List(fooDep1, fooSlugDep))))

      when(serviceConfigsConnector.getBobbyRules()).thenReturn(Future.successful(Map.empty))

      val res = tds.findAllDepsForTeam("foo").futureValue

      res.head.libraryDependencies should contain (fooDep1)
      res.head.libraryDependencies should contain (fooSlugDep)
    }

  }

}
