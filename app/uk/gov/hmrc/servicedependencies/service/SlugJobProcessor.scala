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

import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.{Inject, Singleton}
import java.io.{BufferedInputStream, InputStream}

import akka.stream.Materializer
import play.api.{Configuration, Logger}
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveStreamFactory}
import uk.gov.hmrc.servicedependencies.connector.GzippedResourceConnector
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, SlugDependency, SlugInfo, Version}
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
          .map(_ => slugParserJobsRepository.markProcessed(job.id))
          .recoverWith {
            case NonFatal(e) => Logger.error(s"An error occurred processing slug parser job ${job.id}: ${e.getMessage}", e)
              slugParserJobsRepository.markAttempted(job.id)
          }
      }
      .runWith(Sink.ignore)
      .map(_ => ())

  def processJob(job: MongoSlugParserJob): Future[Unit] =
    futureHelpers.withTimerAndCounter("slug.process")(
      for {
        _     <- Future(Logger.debug(s"processing slug job ${job.id} (${job.slugUri})"))
        is    <- gzippedResourceConnector.openGzippedResource(job.slugUri)
        si    =  SlugParser.parse(job.slugUri, is)
        added <- slugInfoRepository.add(si)
        _     =  if (added) Logger.debug(s"added slugInfo for ${job.slugUri}: ${si.name} ${si.version}")
                 else       Logger.warn(s"slug ${job.slugUri} not added - already processed")
      } yield ()
    )
}


object SlugParser {

  def parse(slugUri: String, in: InputStream): SlugInfo = {
    val tar = new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(in))

    val (runnerVersion, slugVersion, slugName) =
      extractVersionsFromUri(slugUri)
        .getOrElse(sys.error(s"Could not extract slug data from uri $slugUri"))

    val slugInfo = SlugInfo(
      uri             = slugUri,
      name            = slugName,
      version         = slugVersion,
      semanticVersion = Version.parse(slugVersion),
      versionLong     = SlugInfo.toLong(slugVersion).getOrElse(sys.error(s"Could not extract slug data from uri $slugUri")),
      runnerVersion   = runnerVersion,
      classpath       = "",
      jdkVersion      = "",
      dependencies    = List.empty[SlugDependency])

    val Script = s"./$slugName-$slugVersion/bin/$slugName"

    Iterator
      .continually(Try(tar.getNextEntry).recover { case e if e.getMessage == "Stream closed" => null }.get)
      .takeWhile(_ != null)
      .foldLeft(slugInfo)( (result, entry) =>
        (entry.getName match {
          case n if n.startsWith(s"./$slugName")
                 && n.toLowerCase.endsWith(".jar") => extractVersionFromJar(n, tar)
                                                        .map(v => result.copy(dependencies = result.dependencies ++ List(v)))
          case Script                              => extractClasspath(tar)
                                                        .map(cp => result.copy(classpath = cp))
          case "./.jdk/release"                    => extractJdkVersion(tar)
                                                        .map(jdkv => result.copy(jdkVersion = jdkv))
          case _                                   => None
        }).getOrElse(result)
      )
  }

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

  def extractVersionsFromUri(slugUri: String) =
    extractVersionsFromFilename(extractFilename(slugUri))


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

  def extractVersionFromJar(libraryName: String, inputStream: InputStream): Option[SlugDependency] =
    Try {
      val jar = new JarArchiveInputStream(inputStream)
      Iterator
        .continually(jar.getNextJarEntry)
        .takeWhile(_ != null)
        .map(_.getName match {
          //case "reference.conf"                 => None; // TODO: extract reference.conf & send to serviceConfigs
          case "META-INF/MANIFEST.MF"           => extractVersionFromManifest(libraryName, jar).map(Dep.Manifest)
          case file if file.endsWith("pom.xml") => extractVersionFromPom(libraryName, jar).map(Dep.Pom)
          case _                                => None
        })
        .collect { case Some(d) => d }
        .reduceOption(Dep.precedence)
        .map(_.sd)
    }.recover { case e => throw new RuntimeException(s"Could not extract version from $libraryName", e) } // just return None?
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
