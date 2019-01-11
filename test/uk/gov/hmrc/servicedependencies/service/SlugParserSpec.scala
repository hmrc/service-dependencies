package uk.gov.hmrc.servicedependencies.service
import java.io.{BufferedInputStream, ByteArrayInputStream, FileInputStream}
import java.nio.charset.StandardCharsets

import org.scalatest.{FlatSpec, Matchers}

import scala.io.Source

class SlugParserSpec extends FlatSpec with Matchers {

  "extractVersionFromManifest" should "extract return nothing if no version is available in manifest" in {
    val is = new ByteArrayInputStream("Manifest-Version: 1.0".getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromManifest(is) shouldBe None
  }

  it should "extract the correct version when present" in {
    val is = new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromManifest(is) shouldBe Some("1.1.3")
  }


  "extractVersionFromPom" should "extract return nothing if no version is available in manifest" in {
    val is = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromPom(is) shouldBe None
  }

  it should "extract the correct version when present" in {
    val is = new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromPom(is) shouldBe Some("1.2.3")
  }


  "extractVersionFromFilename" should "get version form java style jar names" in {

  }

  it should "get the version from scala style jar names" in {

  }


  it should "handle various edge cases" in {

  }

  "extractConfFromJar" should "extract the version from a jar built with sbt" in {
    val is = new BufferedInputStream(getClass.getResourceAsStream("/slugs/example-ivy_2.11-3.2.0.jar"))
    val output = SlugParser.extractVersionFromJar(is)
    output shouldBe Some("3.2.0")
  }

  it should "extract the version from a jar built with maven" in {
    val is = new BufferedInputStream(getClass.getResourceAsStream("/slugs/example-maven-3.2.5.jar"))
    val output = SlugParser.extractVersionFromJar(is)
    output shouldBe Some("3.2.5")
  }


  "slugparser" should "parse a slug" in {

    val is = new BufferedInputStream(getClass.getResourceAsStream("/slugs/example-service.tar.gz"))
    val res = SlugParser.parse("example-service.tar.gz", is)

    res.length shouldBe 2
    res.find(_.libraryName.contains("example-ivy")).map(_.version) shouldBe Some("3.2.0")
    res.find(_.libraryName.contains("example-maven")).map(_.version) shouldBe Some("3.2.5")
  }


  /****** TEST DATA *******/

  val manifest = """Manifest-Version: 1.0
                   |Implementation-Title: cachecontrol
                   |Implementation-Version: 1.1.3
                   |Specification-Vendor: com.typesafe.play
                   |Specification-Title: cachecontrol
                   |Implementation-Vendor-Id: com.typesafe.play
                   |Specification-Version: 1.1.3
                   |Vcs-Release-Tag: v1.1.3
                   |Implementation-Vendor: com.typesafe.play
                   |Vcs-Release-Hash: 2bcb8fcc9514e01a246b50e19a109ea51130c989
                   |""".stripMargin


  val pom  = """<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
               |  <modelVersion>4.0.0</modelVersion>
               |
               |  <groupId>org.example</groupId>
               |  <artifactId>jpademo</artifactId>
               |  <version>1.2.3</version>
               |  <packaging>jar</packaging>
               |
               |  <name>jpademo</name>
               |  <url>http://maven.apache.org</url>
               |
               |  <properties>
               |    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
               |  </properties>
               |
               |  <dependencies>
               |    <dependency>
               |      <groupId>junit</groupId>
               |      <artifactId>junit</artifactId>
               |      <version>3.8.1</version>
               |      <scope>test</scope>
               |    </dependency>
               |  </dependencies>
               |
               |</project>
               |""".stripMargin
}
