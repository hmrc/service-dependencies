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

package uk.gov.hmrc.servicedependencies.controller

import cats.data.EitherT
import cats.implicits._
import com.google.inject.{Inject, Singleton}
import play.api.libs.functional.syntax._
import play.api.libs.json.{Json, OWrites, __}
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.controller.model.{Dependencies, Dependency, DependencyBobbyRule}
import uk.gov.hmrc.servicedependencies.model._
import uk.gov.hmrc.servicedependencies.persistence.{LatestVersionRepository, MetaArtefactRepository}
import uk.gov.hmrc.servicedependencies.service.{SlugDependenciesService, SlugInfoService, TeamDependencyService}

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceDependenciesController @Inject()(
  slugInfoService        : SlugInfoService
, slugDependenciesService: SlugDependenciesService
, serviceConfigsConnector: ServiceConfigsConnector
, teamDependencyService  : TeamDependencyService
, metaArtefactRepository : MetaArtefactRepository
, latestVersionRepository: LatestVersionRepository
, cc                     : ControllerComponents
)(implicit
  ec                     : ExecutionContext
) extends BackendController(cc) {

  implicit val dw: OWrites[Dependencies] = Dependencies.writes

  def dependenciesForTeam(teamName: String): Action[AnyContent] =
    Action.async { implicit request =>
      for {
        depsWithRules <- teamDependencyService.findAllDepsForTeam(teamName)
      } yield Ok(Json.toJson(depsWithRules))
  }

  def getServicesWithDependency(
    flag        : String,
    group       : String,
    artefact    : String,
    versionRange: String,
    scope       : Option[List[String]]
  ): Action[AnyContent] =
    Action.async { implicit request =>
      implicit val format = ApiServiceDependencyFormats.serviceDependencyFormat
      (for {
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         sc  <- scope.fold(EitherT.pure[Future, Result](Option.empty[List[DependencyScope]]))(
                  _.traverse( s => EitherT.fromEither[Future](DependencyScope.parse(s)).leftMap(BadRequest(_)))
                    .map(Some.apply)
                )
         vr  <- EitherT.fromOption[Future](BobbyVersionRange.parse(versionRange), BadRequest(s"invalid versionRange '$versionRange'"))
         res <- EitherT.right[Result] {
                  slugInfoService
                    .findServicesWithDependency(f, group, artefact, vr, sc)
                }
       } yield Ok(Json.toJson(res))
      ).merge
    }

  def getGroupArtefacts: Action[AnyContent] =
    Action.async {
      implicit val format = GroupArtefacts.apiFormat
      slugInfoService.findGroupsArtefacts
        .map(res => Ok(Json.toJson(res)))
    }

  def slugInfo(name: String, version: Option[String]): Action[AnyContent] =
    Action.async {
      (for {
         slugInfo        <- EitherT.fromOptionF(
                              version match {
                                case Some(version) => slugInfoService.getSlugInfo(name, Version(version))
                                case None          => slugInfoService.getSlugInfo(name, SlugInfoFlag.Latest)
                              },
                              NotFound("")
                            )
         // prefer graph data from meta-artefact if available
         optMetaArtefact <- EitherT.liftF[Future, Result, Option[MetaArtefact]](metaArtefactRepository.find(name, slugInfo.version))
         optModule       =  optMetaArtefact.flatMap(x => x.modules.find(_.name == name).orElse(x.modules.headOption))
         slugInfo2       =  optMetaArtefact.fold(slugInfo)(ma => slugInfo.copy(
                              dependencyDotCompile  = optModule.flatMap(_.dependencyDotCompile).getOrElse(""),
                              dependencyDotProvided = optModule.flatMap(_.dependencyDotProvided).getOrElse(""),
                              dependencyDotTest     = optModule.flatMap(_.dependencyDotTest).getOrElse(""),
                              dependencyDotIt       = optModule.flatMap(_.dependencyDotIt).getOrElse(""),
                              dependencyDotBuild    = ma.dependencyDotBuild.getOrElse("")
                            ))
       } yield {
         implicit val f = ApiSlugInfoFormats.slugInfoFormat
         Ok(Json.toJson(slugInfo2))
       }
      ).merge
    }

  def dependenciesOfSlugForTeam(team: String, flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      (for {
         f    <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         deps <- EitherT.liftF[Future, Result, Map[String, Seq[Dependency]]](
                   teamDependencyService.dependenciesOfSlugsForTeam(team, f)
                 )
       } yield {
         implicit val dw = Dependency.writes
         Ok(Json.toJson(deps))
       }
      ).merge
    }

  def findJDKForEnvironment(team: Option[String], flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      (for {
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         res <- EitherT.liftF[Future, Result, Seq[JDKVersion]](
                  slugInfoService.findJDKVersions(team, f)
                )
       } yield {
         implicit val jdkvf = JDKVersionFormats.jdkVersionFormat
         Ok(Json.toJson(res))
       }
      ).merge
    }

  def findSBTForEnvironment(team: Option[String], flag: String): Action[AnyContent] =
    Action.async { implicit request =>
      (for {
         f   <- EitherT.fromOption[Future](SlugInfoFlag.parse(flag), BadRequest(s"invalid flag '$flag'"))
         res <- EitherT.liftF[Future, Result, Seq[SBTVersion]](
                  slugInfoService.findSBTVersions(team, f)
                )
       } yield {
         implicit val sbtvf = SBTVersionFormats.sbtVersionFormat
         Ok(Json.toJson(res))
       }
      ).merge
    }

  def repositoryName(group: String, artefact: String, version: String): Action[AnyContent] =
    Action.async {
      metaArtefactRepository.findRepoNameByModule(group, artefact, Version(version))
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res))))
    }

  def moduleDependencies(repositoryName: String, versionOption: Option[String]): Action[AnyContent] =
    Action.async {
      for {
        latestVersions <- latestVersionRepository.getAllEntries()
        bobbyRules     <- serviceConfigsConnector.getBobbyRules()
        repositories   <- versionOption match {
                            case Some(version) => (if (version == "latest")
                                                     metaArtefactRepository.find(repositoryName)
                                                   else
                                                     metaArtefactRepository
                                                     .find(repositoryName, Version(version))
                                                  )
                                                    .flatMap {
                                                      case Some(metaArtefact) => Future.successful(toRepository(metaArtefact, latestVersions, bobbyRules))
                                                      case None               => repositoryFromCuratedSlugDependencies(repositoryName, version)
                                                    }
                                                    .map(Seq(_))
                            case None          => metaArtefactRepository
                                                    .findAllVersions(repositoryName)
                                                    .map { _.map { toRepository(_, latestVersions, bobbyRules) }}
                          }
      } yield {
        implicit val rw: OWrites[Repository] = Repository.writes
        Ok(Json.toJson(repositories))
      }
    }

  private def repositoryFromCuratedSlugDependencies(repositoryName: String, version: String): Future[Repository] = for {
    dependencyOption <- version match {
      case "latest" => slugDependenciesService.curatedLibrariesOfSlug(repositoryName, SlugInfoFlag.Latest)
      case version => slugDependenciesService.curatedLibrariesOfSlug(repositoryName, Version(version))
    }
    dependencies = dependencyOption.getOrElse(List())
  } yield Repository(
      name              = repositoryName,
      version           = None,
      dependenciesBuild = dependencies.filter(_.scope.contains(DependencyScope.Build)),
      modules           = Seq(
                            RepositoryModule(
                              name                 = repositoryName,
                              group                = "uk.gov.hmrc",
                              dependenciesCompile  = dependencies.filter(_.scope.contains(DependencyScope.Compile)),
                              dependenciesProvided = dependencies.filter(_.scope.contains(DependencyScope.Provided)),
                              dependenciesTest     = dependencies.filter(_.scope.contains(DependencyScope.Test)),
                              dependenciesIt       = dependencies.filter(_.scope.contains(DependencyScope.It)),
                              crossScalaVersions   = None
                            )
      )
    )

  @deprecated("Use moduleDependencies", "21.02.2023")
  def moduleDependenciesDeprecated(repositoryName: String, version: Option[String]): Action[AnyContent] =
    Action.async {
      (for {
         meta           <- EitherT.fromOptionF(
                             version match {
                               case Some(version)          => metaArtefactRepository.find(repositoryName, Version(version))
                               case None /* i.e. latest */ => metaArtefactRepository.find(repositoryName)
                             },
                             NotFound("")
                           )
         latestVersions <- EitherT.liftF[Future, Result, Seq[LatestVersion]](latestVersionRepository.getAllEntries)
         bobbyRules     <- EitherT.liftF[Future, Result, BobbyRules](serviceConfigsConnector.getBobbyRules())
       } yield {
        val repository = toRepository(meta, latestVersions, bobbyRules)
        implicit val rw = Repository.writes
         Ok(Json.toJson(repository))
       }
      ).leftFlatMap(_ =>
        // fallback to data from curatedLibrariesOfSlug
        for {
          dependencies <- version match {
                            case None          => EitherT.fromOptionF(slugDependenciesService.curatedLibrariesOfSlug(repositoryName, SlugInfoFlag.Latest), NotFound(""))
                            case Some(version) => EitherT.fromOptionF(slugDependenciesService.curatedLibrariesOfSlug(repositoryName, Version(version)), NotFound(""))
                          }
        } yield {
          implicit val rw = Repository.writes
          Ok(
            Json.toJson(
              Repository(
                name              = repositoryName,
                version           = None,
                dependenciesBuild = dependencies.filter(_.scope == Some(DependencyScope.Build)),
                modules           = Seq(
                                      RepositoryModule(
                                        name                 = repositoryName,
                                        group                = "uk.gov.hmrc",
                                        dependenciesCompile  = dependencies.filter(_.scope == Some(DependencyScope.Compile)),
                                        dependenciesProvided = dependencies.filter(_.scope == Some(DependencyScope.Provided)),
                                        dependenciesTest     = dependencies.filter(_.scope == Some(DependencyScope.Test)),
                                        dependenciesIt       = dependencies.filter(_.scope == Some(DependencyScope.It)),
                                        crossScalaVersions   = None
                                      )
                                    )
              )
            )
          )
        }
      ).merge
    }

  private def toRepository(meta: MetaArtefact, latestVersions: Seq[LatestVersion], bobbyRules: BobbyRules) = {
    def toDependencies(name: String, scope: DependencyScope, dotFile: String) =
      slugDependenciesService.curatedLibrariesFromGraph(
        dotFile        = dotFile,
        rootName       = name,
        latestVersions = latestVersions,
        bobbyRules     = bobbyRules,
        scope          = scope
      )

    val sbtVersion =
    // sbt-versions will be the same for all modules,
      meta.modules.flatMap(_.sbtVersion.toSeq).headOption

    def when[T](b: Boolean)(seq: => Seq[T]): Seq[T] =
      if (b) seq
      else Nil

    def dependencyIfMissing(
      dependencies: Seq[Dependency],
      group       : String,
      artefact    : String,
      versions    : Seq[Version],
      scope       : DependencyScope
    ): Seq[Dependency] =
      for {
        v <- versions
        if dependencies.find(dep => dep.group == group && dep.name == artefact).isEmpty
      } yield
        Dependency(
          name                = artefact,
          group               = group,
          currentVersion      = v,
          latestVersion       = latestVersions.find(d => d.name == artefact && d.group == group).map(_.version),
          bobbyRuleViolations = List.empty,
          importBy            = None,
          scope               = Some(scope)
        )

    val buildDependencies = meta.dependencyDotBuild.fold(Seq.empty[Dependency])(s => toDependencies(meta.name, DependencyScope.Build, s))

    Repository(
      name              = meta.name,
      version           = Some(meta.version),
      dependenciesBuild = buildDependencies ++
                            dependencyIfMissing(
                              buildDependencies,
                              group    = "org.scala-sbt",
                              artefact = "sbt",
                              versions = sbtVersion.toSeq,
                              scope    = DependencyScope.Build
                            ),
      modules           = meta.modules
                              .filter(_.publishSkip.fold(true)(!_))
                              .map { m =>
                                val compileDependencies  = m.dependencyDotCompile .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Compile , s))
                                val providedDependencies = m.dependencyDotProvided.fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Provided, s))
                                val testDependencies     = m.dependencyDotTest    .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.Test    , s))
                                val itDependencies       = m.dependencyDotIt      .fold(Seq.empty[Dependency])(s => toDependencies(m.name, DependencyScope.It      , s))
                                val scalaVersions        = m.crossScalaVersions.toSeq.flatten
                                val (activeBobbyRuleViolations, pendingBobbyRuleViolations) =
                                  bobbyRules.violationsFor(
                                    group   = m.group,
                                    name    = m.name,
                                    version = meta.version
                                  ).partition(rule => LocalDate.now().isAfter(rule.from))
                                RepositoryModule(
                                  name                 = m.name,
                                  group                = m.group,
                                  dependenciesCompile  = compileDependencies ++
                                                           when(compileDependencies.nonEmpty)(
                                                             dependencyIfMissing(
                                                               dependencies = compileDependencies,
                                                               group        = "org.scala-lang",
                                                               artefact     = "scala-library",
                                                               versions     = scalaVersions,
                                                               scope        = DependencyScope.Compile
                                                             )
                                                           ),
                                  dependenciesProvided = providedDependencies,
                                  dependenciesTest     = testDependencies ++
                                                           when(testDependencies.nonEmpty)(
                                                             dependencyIfMissing(
                                                               dependencies = testDependencies,
                                                               group        = "org.scala-lang",
                                                               artefact     = "scala-library",
                                                               versions     = scalaVersions,
                                                               scope        = DependencyScope.Test
                                                             )
                                                           ),
                                  dependenciesIt       = itDependencies ++
                                                           when(itDependencies.nonEmpty)(
                                                             dependencyIfMissing(
                                                               dependencies = itDependencies,
                                                               group        = "org.scala-lang",
                                                               artefact     = "scala-library",
                                                               versions     = scalaVersions,
                                                               scope        = DependencyScope.It
                                                             )
                                                           ),
                                  crossScalaVersions   = m.crossScalaVersions,
                                  activeBobbyRuleViolations,
                                  pendingBobbyRuleViolations
                                )
                              }
    )
  }

  def latestVersion(group: String, artefact: String): Action[AnyContent] =
    Action.async {
      latestVersionRepository.find(group, artefact)
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res)(LatestVersion.apiWrites))))
    }

  def metaArtefact(repository: String, version: Option[String]): Action[AnyContent] =
    Action.async {
      version
        .fold(metaArtefactRepository.find(repository))(v => metaArtefactRepository.find(repository, Version(v)))
        .map(_.fold(NotFound(""))(res => Ok(Json.toJson(res)(MetaArtefact.apiFormat))))
    }
}

case class Repository(
  name             : String,
  version          : Option[Version], // optional since we don't have this when reshaping old data
  dependenciesBuild: Seq[Dependency],
  modules          : Seq[RepositoryModule]
)

object Repository {
  val writes: OWrites[Repository] = {
    implicit val dw  = Dependency.writes
    implicit val rmw = RepositoryModule.writes
    implicit val vf  = Version.format
    ( (__ \ "name"             ).write[String]
    ~ (__ \ "version"          ).writeNullable[Version]
    ~ (__ \ "dependenciesBuild").write[Seq[Dependency]]
    ~ (__ \ "modules"          ).write[Seq[RepositoryModule]]
    )(unlift(Repository.unapply))
  }
}

case class RepositoryModule(
  name                : String,
  group               : String,
  dependenciesCompile : Seq[Dependency],
  dependenciesProvided: Seq[Dependency],
  dependenciesTest    : Seq[Dependency],
  dependenciesIt      : Seq[Dependency],
  crossScalaVersions  : Option[List[Version]],
  activeBobbyRules    : Seq[DependencyBobbyRule] = Seq(),
  pendingBobbyRules   : Seq[DependencyBobbyRule] = Seq()
)

object RepositoryModule {
  val writes: OWrites[RepositoryModule] = {
    implicit val bf = DependencyBobbyRule.writes
    implicit val dw = Dependency.writes
    implicit val vf = Version.format
    ( (__ \ "name"                ).write[String]
    ~ (__ \ "group"               ).write[String]
    ~ (__ \ "dependenciesCompile" ).write[Seq[Dependency]]
    ~ (__ \ "dependenciesProvided").write[Seq[Dependency]]
    ~ (__ \ "dependenciesTest"    ).write[Seq[Dependency]]
    ~ (__ \ "dependenciesIt"      ).write[Seq[Dependency]]
    ~ (__ \ "crossScalaVersions"  ).write[Seq[Version]].contramap[Option[Seq[Version]]](_.getOrElse(Seq.empty))
    ~ (__ \ "activeBobbyRules"    ).write[Seq[DependencyBobbyRule]]
    ~ (__ \ "pendingBobbyRules"   ).write[Seq[DependencyBobbyRule]]
    )(unlift(RepositoryModule.unapply))
  }
}
