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
import uk.gov.hmrc.servicedependencies.model.{MongoSlugParserJob, SlugDependencyInfo, SlugLibraryVersion}
import uk.gov.hmrc.servicedependencies.persistence.{SlugDependencyRepository, SlugParserJobsRepository}
import uk.gov.hmrc.servicedependencies.util.FutureHelpers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.{Success, Try}
import scala.util.control.NonFatal

class SlugParser @Inject()(
   actorSystem             : ActorSystem,
   slugParserJobsRepository: SlugParserJobsRepository,
   slugDependencyRepository: SlugDependencyRepository,
   slugConnector           : SlugConnector,
   futureHelpers           : FutureHelpers) {

   import ExecutionContext.Implicits.global


  val slugParserActor: ActorRef = actorSystem.actorOf(
      Props(new SlugParser.SlugParserActor(
          slugParserJobsRepository,
          slugDependencyRepository,
          slugConnector,
          futureHelpers))
        .withRouter(FromConfig())
    , "slugParserActor")

  // TODO to be called from S3 notification too
  def runSlugParserJobs() =
    slugParserJobsRepository
      .getUnprocessed
      .map { jobs =>
        Logger.debug(s"found ${jobs.size} Slug parser jobs")
        jobs.map {
          job => slugParserActor ! SlugParser.RunJob(job)
        }
      }
      .recover {
        case NonFatal(e) => Logger.error(s"An error occurred processing slug parser jobs: ${e.getMessage}", e)
      }
}


object SlugParser {

  case class RunJob(job: MongoSlugParserJob)

  class SlugParserActor @Inject()(
    slugParserJobsRepository: SlugParserJobsRepository,
    slugDependencyRepository: SlugDependencyRepository,
    slugConnector           : SlugConnector,
    futureHelpers           : FutureHelpers) extends Actor {

    import context.dispatcher

    def receive = {
      case RunJob(job) =>
        Logger.debug(s">>>>>>>>>>>>>>> running job $job")

        val f = futureHelpers.withTimerAndCounter("slug.process")(
          for {
            slvs <- slugConnector.downloadSlug(job.slugUri)(in => SlugParser.parse(job.slugName, in))
            // TODO what if already added
            sld  =  SlugDependencyInfo(
                      slugName     = job.slugName,
                      slugUri      = job.slugUri,
                      dependencies = slvs)
            _    <- slugDependencyRepository.add(sld)
            _ = Logger.debug(s"added {$job.id}: $sld")
            _    <- job.id
                      .map(slugParserJobsRepository.markProcessed)
                      .getOrElse(sys.error("No id on job!")) // shouldn't happen
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

  def parse(slugName: String, gz: BufferedInputStream): Seq[SlugLibraryVersion] =
    extractTarGzip(gz)
    .map { tar =>
      Stream
        .continually(tar.getNextEntry)
        .takeWhile(_ != null)
        .flatMap {
          case next if next.getName.toLowerCase.endsWith(".jar") =>
            extractVersionFromJar(tar).map(v => SlugLibraryVersion(slugName, next.getName, v.toString)) // TODO tar is closed prematurely for second reading...
          case _ => None
        }
        .toList // make strict - gz will not be open forever...
    }
    .getOrElse(Seq.empty)

  def extractTarGzip(gz: BufferedInputStream): Try[ArchiveInputStream] =
    Try(new CompressorStreamFactory().createCompressorInputStream(gz))
      .map(is => new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(is)))

  def extractVersionFromJar(inputStream: InputStream): Option[String] = {
    val jar = new JarArchiveInputStream(inputStream)
    Stream
      .continually(jar.getNextJarEntry)
      .takeWhile(_ != null)
      .flatMap(entry => {
        entry.getName match {
          //case "reference.conf" => None; // TODO: extract reference.conf & send to serviceConfigs
          case "META-INF/MANIFEST.MF"           => extractVersionFromManifest(jar)
          case file if file.endsWith("pom.xml") => extractVersionFromPom(jar)
          case _                                => None; // skip
        }
      }).headOption
  }


  def extractVersionFromManifest(in: InputStream): Option[String] = {
    val regex = "Implementation-Version: (.+)".r
    val manifest = Source.fromInputStream(in).mkString
    regex.findFirstMatchIn(manifest).map(_.group(1))
  }


  def extractVersionFromPom(in: InputStream): Option[String] = {
    import xml._
    Try(XML.load(in)).map(pom => (pom \ "version").headOption.map(_.text)).getOrElse(None)
  }


  def extractVersionFromFilename(fileName: String): Option[String] = None


}
