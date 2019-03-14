/*
 * Copyright 2019 HM Revenue & Customs
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

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.{Inject, Singleton}
import java.io.{BufferedInputStream, InputStream}
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveStreamFactory}
import play.api.{Configuration, Logger}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicedependencies.connector.{
  GzippedResourceConnector, TeamsAndRepositoriesConnector, TeamsForServices
}
import uk.gov.hmrc.servicedependencies.model.{Config, MongoSlugParserJob, SlugDependency, SlugInfo, Version}
import uk.gov.hmrc.servicedependencies.persistence.{SlugInfoRepository, SlugParserJobsRepository}
import uk.gov.hmrc.servicedependencies.util.FutureHelpers

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

@Singleton
class SlugJobProcessor @Inject()(
   slugParserJobsRepository: SlugParserJobsRepository,
   slugInfoRepository      : SlugInfoRepository,
   gzippedResourceConnector: GzippedResourceConnector,
   futureHelpers           : FutureHelpers)(
   implicit val materializer: Materializer) {

  import ExecutionContext.Implicits.global

  def run(): Future[Unit] =
    Source.fromFuture(slugParserJobsRepository.getUnprocessed)
      .map { jobs => Logger.debug(s"found ${jobs.size} Slug parser jobs"); jobs }
      .mapConcat(_.toList)
      .mapAsyncUnordered(2) { job =>
        processJob(job)
          .map(_ => slugParserJobsRepository.markProcessed(job.slugUri))
          .recoverWith {
            case NonFatal(e) => Logger.error(s"An error occurred processing slug parser job ${job.slugUri}: ${e.getMessage}", e)
                                slugParserJobsRepository.incAttempts(job.slugUri)
          }
      }
      .runWith(Sink.ignore)
      .map(_ => ())


  def processJob(job: MongoSlugParserJob): Future[Unit] =
    futureHelpers.withTimerAndCounter("slug.process")(
      for {
        _        <- Future(Logger.debug(s"processing slug job ${job.slugUri}"))
        is       <- gzippedResourceConnector.openGzippedResource(job.slugUri)
        si       =  SlugParser.parse(job.slugUri, is)
        added    <- slugInfoRepository.add(si)
        isLatest <- slugInfoRepository.getSlugInfos(name = si.name, optVersion = None)
                      .map { case Nil      => true
                             case nonempty => val isLatest = nonempty.map(_.version).max == si.version
                                              Logger.info(s"Slug ${si.name} ${si.version} isLatest=${isLatest} (out of: ${nonempty.map(_.version).sorted})")
                                              isLatest
                           }
        _        <- if (isLatest) slugInfoRepository.markLatest(si.name, si.version)
                    else Future(())
        _        =  if (added) Logger.debug(s"added slugInfo for ${job.slugUri}: ${si.name} ${si.version}")
                    else       Logger.warn(s"slug ${job.slugUri} not added - already processed")
      } yield ()
    )

  // TEMP
  val slugUri = "https://webstore.tax.service.gov.uk/slugs/service-dependencies/service-dependencies_1.55.0_0.5.2.tgz"
  (for {
    is <- gzippedResourceConnector.openGzippedResource(slugUri)
    si =  SlugParser.parse(slugUri, is)
    _  =  Logger.warn(s"-------------- $slugUri: extracted ${si.configs.mkString(",")}")
  } yield ()
  ).recover { case e => Logger.error(s"Parse failed: ${e.getMessage}", e); () }

}


object SlugParser {

  def parse(slugUri: String, in: InputStream): SlugInfo = {
    val tar = new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(in))

    val (runnerVersion, semanticVersion, slugName) =
      (for {
         (runnerVersion, slugVersion, slugName) <- extractVersionsFromUri(slugUri)
         semanticVersion                        <- Version.parse(slugVersion)
       } yield (runnerVersion, semanticVersion, slugName)
      ).getOrElse(sys.error(s"Could not extract slug data from uri $slugUri"))

    val slugInfo = SlugInfo(
      uri           = slugUri,
      name          = slugName,
      version       = semanticVersion,
      teams         = List.empty,
      runnerVersion = runnerVersion,
      classpath     = "",
      jdkVersion    = "",
      dependencies  = List.empty,
      configs       = List.empty,
      latest        = false)

    val Script = s"./$slugName-$semanticVersion/bin/$slugName"

    Iterator
      .continually(Try(tar.getNextEntry).recover { case e if e.getMessage == "Stream closed" => null }.get)
      .takeWhile(_ != null)
      .foldLeft(slugInfo)( (result, entry) =>
        entry.getName match {
          case n if n.startsWith(s"./$slugName")
                 && n.toLowerCase.endsWith(".jar") => val (optDependency, configs) = parseJar(n, tar)
                                                      result.copy(dependencies = result.dependencies ++ optDependency.toList)
                                                            .copy(configs      = result.configs      ++ configs)
          case Script                              => result.copy(classpath    = extractClasspath(tar).getOrElse(result.classpath))
          case "./.jdk/release"                    => result.copy(jdkVersion   = extractJdkVersion(tar).getOrElse(result.jdkVersion))
          case _                                   => result
        }
      )
  }

  def extractSlugNameFromUri(slugUri: String): Option[String] =
    slugUri.split("/").lastOption.map(_.replaceAll("""_.+\.tgz""", ""))

  def extractFilename(slugUri: String): String =
    // e.g. https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz
    slugUri
      .stripSuffix(".tgz").stripSuffix(".tar.gz")
      .split("/")
      .last

  /** @return (SlugRunnerVersion, SlugVersion, SlugName) */
  def extractVersionsFromFilename(filename: String): Option[(String, String, String)] =
    Try {
      // e.g. https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz
      // val regex = """^\/?(.+)_(\d+\.\d+\.\d+-?.*)_(\d+\.\d+\.\d+)\.tgz$""".r
      val runnerVersion :: slugVersion :: rest = filename.split("_").reverse.toList
      (runnerVersion, slugVersion, rest.mkString("_"))
    }.toOption

  def extractVersionsFromUri(slugUri: String): Option[(String, String, String)] =
    extractVersionsFromFilename(extractFilename(slugUri))

  def extractVersionFromUri(slugUri: String): Option[Version] =
    extractVersionsFromUri(slugUri)
      .flatMap {
        case (_, vStr, _) => Version.parse(vStr)
      }

  sealed trait Dep { def sd: SlugDependency }
  object Dep {
    case class Manifest(sd: SlugDependency) extends Dep
    case class Pom     (sd: SlugDependency) extends Dep

    def precedence(l: Dep, r: Dep): Dep =
      (l, r) match {
        case (l: Dep.Pom     , _              ) => l
        case (_              , r : Dep.Pom    ) => r
        case (l: Dep.Manifest, _              ) => l
        case (_              , r: Dep.Manifest) => r
      }
  }

  def parseJar(libraryName: String, inputStream: InputStream): (Option[SlugDependency], List[Config]) =
    Try {
      val jar = new JarArchiveInputStream(inputStream)
      val l = Iterator
        .continually(jar.getNextJarEntry)
        .takeWhile(_ != null)
        .map(_.getName match {
          case "META-INF/MANIFEST.MF"           => (extractVersionFromManifest(libraryName, jar).map(Dep.Manifest), None)
          case file if file.endsWith("pom.xml") => (extractVersionFromPom(libraryName, jar).map(Dep.Pom), None)
          case "reference.conf"                 => (None, Some(Config( path    = s"$libraryName#reference.conf"
                                                                     , content = scala.io.Source.fromInputStream(jar).mkString
                                                                     ))) // TODO: send tcontent serviceConfigs
          case n if n.endsWith(".conf")         => (None, Some(Config( path    = s"$libraryName#$n"
                                                                     , content = scala.io.Source.fromInputStream(jar).mkString
                                                                     ))) // TODO: keep this only if referenced with `include <n>.conf`
          case _                                => (None, None)
        }).toList
        val optSlugDependency = l
          .collect { case (Some(d), _) => d }
          .reduceOption(Dep.precedence)
          .map(_.sd)
        val configs = l
          .collect { case (_, Some(c)) => c }
        (optSlugDependency, configs)
    }.recover { case e => throw new RuntimeException(s"Could not parse $libraryName: ${e.getMessage}", e) } // just return None?
    .get


  def extractClasspath(inputStream: InputStream) : Option[String] = {
    val prefix = "declare -r app_classpath=\""
    scala.io.Source.fromInputStream(inputStream)
      .getLines
      .find(_.startsWith(prefix))
      .map(_.replace(prefix, "").replace("\"", ""))
  }

  def extractJdkVersion(inputStream: InputStream): Option[String] = {
    val prefix = "JAVA_VERSION="
    scala.io.Source.fromInputStream(inputStream)
      .getLines
      .find(_.startsWith(prefix))
      .map(_.replace(prefix, "").replace("\"", ""))
  }


  def extractVersionFromManifest(libraryName: String, in: InputStream): Option[SlugDependency] = {
    val versionRegex  = "Implementation-Version: (.+)".r
    val groupRegex    = "Implementation-Vendor-Id: (.+)".r
    val artifactRegex = "Implementation-Title: (.+)".r

    for {
      manifest <- Option(scala.io.Source.fromInputStream(in).mkString)
      version  <- versionRegex.findFirstMatchIn(manifest).map(_.group(1))
      group    <- groupRegex.findFirstMatchIn(manifest).map(_.group(1))
      artifact <- artifactRegex.findFirstMatchIn(manifest).map(_.group(1).toLowerCase)
    } yield SlugDependency(libraryName, version, group, artifact, meta = "fromManifest")
  }


  def extractVersionFromPom(libraryName: String, in: InputStream): Option[SlugDependency] = {
    import xml._
    for {
      raw      <- Try(scala.io.Source.fromInputStream(in).mkString).toOption
      pom      <- Try(XML.loadString(raw)).toOption
      version  <- (pom \ "version").headOption.getOrElse(pom \ "parent" \ "version").map(_.text).headOption
      group    <- (pom \ "groupId").headOption.getOrElse(pom \ "parent" \ "groupId").map(_.text).headOption
      artifact <- (pom \ "artifactId").headOption.map(_.text)
    } yield SlugDependency(libraryName, version, group, artifact, meta = "fromPom")
  }
}
