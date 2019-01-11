package uk.gov.hmrc.servicedependencies.service
import java.io.{BufferedInputStream, InputStream}

import org.apache.commons.compress.archivers.jar.JarArchiveInputStream
import org.apache.commons.compress.archivers.{ArchiveInputStream, ArchiveStreamFactory}
import org.apache.commons.compress.compressors.CompressorStreamFactory
import uk.gov.hmrc.servicedependencies.model.SlugLibraryVersion

import scala.io.Source
import scala.util.{Success, Try}


object SlugParser {

  def parse(slugName: String, gz: BufferedInputStream): Seq[SlugLibraryVersion] = {
    extractTarGzip(gz)
    .map(tar => {
      Stream
        .continually(tar.getNextEntry)
        .takeWhile(_ != null)
        .flatMap {
          case next if next.getName.toLowerCase.endsWith(".jar") =>
            extractVersionFromJar(tar).map(v => SlugLibraryVersion(slugName, next.getName, v.toString))
          case _ => None
        }
    })
    .getOrElse(Seq.empty)
  }

  def extractTarGzip(gz: BufferedInputStream): Try[ArchiveInputStream] = {
    Try(new CompressorStreamFactory().createCompressorInputStream(gz))
      .map(is => new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(is)))
  }

  def extractVersionFromJar(inputStream: InputStream) : Option[String] = {

    val jar = new JarArchiveInputStream(inputStream)

    Stream
    .continually(jar.getNextJarEntry)
    .takeWhile(_ != null)
    .flatMap(entry => {
      entry.getName match {
        //case "reference.conf" => None; // TODO: extract reference.conf & send to serviceConfigs
        case "META-INF/MANIFEST.MF"     => extractVersionFromManifest(jar)
        case file if file.endsWith("pom.xml") => extractVersionFromPom(jar)
        case _                          => None; // skip
      }
    }).headOption

  }


  def extractVersionFromManifest(in: InputStream): Option[String] = {
    val regex = "Implementation-Version: (.+)".r
    val manifest = Source.fromInputStream(in).mkString
    regex.findFirstMatchIn(manifest).map(_.group(1))
  }


  def extractVersionFromPom(in: InputStream) :Option[String] = {
    import xml._
    Try(XML.load(in)).map( pom => (pom \ "version").headOption.map(_.text)).getOrElse(None)
  }


  def extractVersionFromFilename(fileName: String): Option[String] = None


}
