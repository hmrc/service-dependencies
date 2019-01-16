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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.routing.{FromConfig, RoundRobinPool}
import play.api.Logger
import java.io.{BufferedInputStream, InputStream}
import java.util.concurrent.Executors
import javax.inject.{Inject, Named}
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream
import org.apache.commons.compress.archivers.{ArchiveInputStream, ArchiveStreamFactory}
import org.apache.commons.compress.compressors.CompressorStreamFactory
import uk.gov.hmrc.servicedependencies.connector.SlugConnector
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, SlugInfo, SlugDependency}
import uk.gov.hmrc.servicedependencies.persistence.{SlugInfoRepository, SlugParserJobsRepository}
import uk.gov.hmrc.servicedependencies.util.FutureHelpers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.{Success, Try}
import scala.util.control.NonFatal

class SlugParser @Inject()(
   actorSystem             : ActorSystem,
   slugParserJobsRepository: SlugParserJobsRepository,
   slugInfoRepository      : SlugInfoRepository,
   slugConnector           : SlugConnector,
   futureHelpers           : FutureHelpers) {

  import ExecutionContext.Implicits.global


  val slugParserActor: ActorRef =
    actorSystem.actorOf(
      Props(new SlugParser.SlugParserActor(
          slugParserJobsRepository,
          slugInfoRepository,
          slugConnector,
          futureHelpers))
        .withRouter(FromConfig()),
      "slugParserActor")

  def runSlugParserJobs(): Future[Unit] =
    slugParserJobsRepository
      .getUnprocessed
      .map { jobs =>
        Logger.debug(s"found ${jobs.size} Slug parser jobs")
        jobs.map {
          job => slugParserActor ! SlugParser.RunJob(job)
        }
      }
}


object SlugParser {

  case class RunJob(job: MongoSlugParserJob)

  class SlugParserActor @Inject()(
    slugParserJobsRepository: SlugParserJobsRepository,
    slugInfoRepository      : SlugInfoRepository,
    slugConnector           : SlugConnector,
    futureHelpers           : FutureHelpers) extends Actor {

    import context.dispatcher

    def receive = {
      case RunJob(job) =>
        Logger.debug(s">>>>>>>>>>>>>>> running job $job")

        val f = futureHelpers.withTimerAndCounter("slug.process")(
          for {
            si <- slugConnector.downloadSlug(job.slugUri)(in => SlugParser.parse(job.slugUri, in).get)
            _  <- slugInfoRepository.add(si)
            _  =  Logger.debug(s"added {$job.id}: $si")
            _  <- slugParserJobsRepository.markProcessed(job.id)
          } yield ()
        ).recover {
          case NonFatal(e) => Logger.error(s"An error occurred processing slug parser job ${job.id}: ${e.getMessage}", e)
        }

        // blocking so that number of actors determines throughput
        Await.result(f, 10.minute)
    }

    override def preRestart(reason: Throwable, message: Option[Any]) = {
      super.preRestart(reason, message)
      Logger.error(s"Restarting due to ${reason.getMessage} when processing $message", reason)
    }
  }

  def parse(slugUri: String, gz: BufferedInputStream): Try[SlugInfo] =
    extractTarGzip(gz)
    .map { tar =>
      val slugDependencies =
        Stream
          .continually(tar.getNextEntry)
          .takeWhile(_ != null)
          .flatMap {
            case next if next.getName.toLowerCase.endsWith(".jar") =>
              extractVersionFromJar(next.getName, tar)
            case _ => None
          }
          .toList // make strict - gz will not be open forever...


        val (runnerVersion, slugVersion, slugName) = extractFromUri(slugUri)

        SlugInfo(
          slugUri       = slugUri,
          slugName      = slugName,
          slugVersion   = slugVersion,
          runnerVersion = runnerVersion,
          classpath     = "TODO",
          dependencies  = slugDependencies)
    }

    def extractFromUri(slugUri: String): (String, String, String) = {
      // e.g. https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz
      val filename = slugUri
                       .stripSuffix(".tgz").stripSuffix(".tar.gz")
                       .split("/")
                       .lastOption
                       .getOrElse(sys.error(s"Could not extract slug data from uri $slugUri"))
      val runnerVersion :: slugVersion :: rest = filename.split("_").reverse.toList
      (runnerVersion, slugVersion, rest.mkString("_"))
    }

  def extractTarGzip(gz: BufferedInputStream): Try[ArchiveInputStream] =
    Try(new CompressorStreamFactory().createCompressorInputStream(gz))
      .map(is => new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(is)))

  def extractVersionFromJar(libraryName: String, inputStream: InputStream): Option[SlugDependency] = {
    val jar = new JarArchiveInputStream(inputStream)
    Stream
      .continually(jar.getNextJarEntry)
      .takeWhile(_ != null)
      .flatMap { entry =>
        entry.getName match {
          //case "reference.conf" => None; // TODO: extract reference.conf & send to serviceConfigs
          case "META-INF/MANIFEST.MF"           => extractVersionFromManifest(libraryName, jar)
          case file if file.endsWith("pom.xml") => extractVersionFromPom(libraryName, jar)
          case _                                => None; // skip
        }
      }.headOption
  }


  def extractVersionFromManifest(libraryName: String, in: InputStream): Option[SlugDependency] = {
    val versionRegex    = "Implementation-Version: (.+)".r
    val groupRegex      = "Implementation-Vendor-Id: (.+)".r
    val artifactRegex   = "Implementation-Title: (.+)".r

    for {
      manifest  <- Option(Source.fromInputStream(in).mkString)
      version   <- versionRegex.findFirstMatchIn(manifest).map(_.group(1))
      group     <- groupRegex.findFirstMatchIn(manifest).map(_.group(1))
      artifact  <- artifactRegex.findFirstMatchIn(manifest).map(_.group(1))
    } yield SlugDependency(libraryName, version, group, artifact)

  }


  def extractVersionFromPom(libraryName: String, in: InputStream): Option[SlugDependency] = {

    import xml._

    for {
      raw <- Try(scala.io.Source.fromInputStream(in).mkString).toOption
      pom <-  Try(XML.loadString(raw)).toOption
      version  <- (pom \ "version").headOption.getOrElse(pom \ "parent" \ "version").map(_.text).headOption
      group    <- (pom \ "groupId").headOption.getOrElse(pom \ "parent" \ "groupId").map(_.text).headOption
      artifact <- (pom \ "artifactId").headOption.map(_.text)
    } yield SlugDependency(libraryName, version, group, artifact)

  }


  def extractVersionFromFilename(fileName: String): Option[String] = {
    val regex = """^\/?(.+)_(\d+\.\d+\.\d+-?.*)_(\d+\.\d+\.\d+)\.tgz$""".r
    // TODO: write a function to turn a slugname/full uri to version name
    ???
  }



}
