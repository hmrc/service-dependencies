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
import java.io.{BufferedInputStream, ByteArrayInputStream}
import java.nio.charset.StandardCharsets

import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.scalatest.{FlatSpec, Matchers}
import uk.gov.hmrc.servicedependencies.model.SlugDependency

class SlugParserSpec extends FlatSpec with Matchers {

  "extractVersionFromManifest" should "extract return nothing if no version is available in manifest" in {
    val is = new ByteArrayInputStream("Manifest-Version: 1.0".getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromManifest("testlib.jar", is) shouldBe None
  }

  it should "extract the correct version when present" in {
    val is = new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromManifest("testlib.jar", is) shouldBe Some(SlugDependency("testlib.jar",
      version  = "1.1.3",
      group    = "com.typesafe.play",
      artifact = "cachecontrol",
      meta     = "fromManifest"))
  }


  "extractVersionFromPom" should "extract return nothing if no version is available in manifest" in {
    val is = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromPom("",is) shouldBe None
  }

  it should "extract the correct version when present" in {
    val is = new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromPom("testlib.jar", is) shouldBe Some(SlugDependency("testlib.jar",
      version  = "1.2.3",
      group    = "org.example",
      artifact = "jpademo",
      meta     ="fromPom"))
  }

  it should "extract the correct version info from a pom with a parent group" in {
    val is = new ByteArrayInputStream(pomParent.getBytes(StandardCharsets.UTF_8))
    SlugParser.extractVersionFromPom("testlibparent.jar", is) shouldBe Some(SlugDependency("testlibparent.jar",
      version  = "1.7.25",
      group    = "org.slf4j",
      artifact = "jul-to-slf4j",
      meta     = "fromPom"))
  }

  "extractConfFromJar" should "extract the version from a jar built with sbt" in {
    val is = new BufferedInputStream(getClass.getResourceAsStream("/slugs/example-ivy_2.11-3.2.0.jar"))
    val output = SlugParser.extractVersionFromJar("bob.jar", is).get
    output.version   shouldBe "3.2.0"
    output.group     shouldBe "uk.gov.hmrc"
    output.artifact shouldBe "time"
  }

  it should "extract the version from a jar built with maven" in {
    val is = new BufferedInputStream(getClass.getResourceAsStream("/slugs/example-maven-3.2.5.jar"))
    val output = SlugParser.extractVersionFromJar("bob.jar", is).get
    output.version  shouldBe "1.2.3"
    output.group    shouldBe "com.test"
    output.artifact shouldBe "mavenlibrary"
  }

  "extractFilename" should "extract the filename from Uri" in {
     SlugParser.extractFilename("https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz")    shouldBe "my-slug_0.27.0_0.5.2"
     SlugParser.extractFilename("https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tar.gz") shouldBe "my-slug_0.27.0_0.5.2"
  }

  "extractFromUri" should "extract the runnerVersion, slugVersion and slugName from Filename" in {
    val Some((runnerVersion, slugVersion, slugName)) = SlugParser.extractVersionsFromFilename("my-slug_0.27.0_0.5.2")
    runnerVersion shouldBe "0.5.2"
    slugVersion   shouldBe "0.27.0"
    slugName      shouldBe "my-slug"
  }


  "slugparser" should "parse a slug" in {
    val in = getClass.getResourceAsStream("/slugs/example-service.tar")
    val res = SlugParser.parse("example-service_0.27.0_0.5.2.tar.gz", in)

    res.name shouldBe "example-service"
    res.runnerVersion shouldBe "0.5.2"
    res.version shouldBe "0.27.0"

    res.classpath.isEmpty shouldBe false
    res.classpath shouldNot startWith ("declare -r app_classpath")

    res.jdkVersion shouldBe "1.8.0_172"

    res.dependencies.length shouldBe 2

    val ivy = res.dependencies.find(_.path.contains("example-ivy")).get
    ivy.version  shouldBe "3.2.0"
    ivy.group    shouldBe "uk.gov.hmrc"
    ivy.artifact shouldBe "time"

    val maven = res.dependencies.find(_.path.contains("example-maven")).get
    maven.version  shouldBe "1.2.3"
    maven.group    shouldBe "com.test"
    maven.artifact shouldBe "mavenlibrary"
  }

  "extractClasspath" should "strip the prefix and quotes" in {
    val cp = "declare -r app_classpath=\"$lib_dir/org.apache.commons.commons-lang3-3.6.jar:$lib_dir/javax.transaction.jta-1.1.jar\""
    val is = new ByteArrayInputStream(cp.getBytes(StandardCharsets.UTF_8))
    val result = SlugParser.extractClasspath(is)
    result shouldBe Some("$lib_dir/org.apache.commons.commons-lang3-3.6.jar:$lib_dir/javax.transaction.jta-1.1.jar")
  }

  it should "not extract anything if no classpath is present" in {
    val cp = """declare -r script_conf_file="../conf/application.ini"""
    val is = new ByteArrayInputStream(cp.getBytes(StandardCharsets.UTF_8))
    val result = SlugParser.extractClasspath(is)
    result shouldBe None
  }

  "extractJdkVersion" should "strip the prefix and quotes" in {
    val release =
      """JAVA_VERSION="1.8.0_172"
        |OS_NAME="Linux"
        |OS_VERSION="2.6"
        |OS_ARCH="amd64"
        |SOURCE=" .:33d274a7dda0 corba:875a75c440cd deploy:93653a78dd93 hotspot:32ba4d2121c1 hotspot/make/closed:d7fcb23b86c8 hotspot/src/closed:0536bda62571 install:30a516c2a631 jaxp:30586bb50743 jaxws:452a6a5a878e jdk:5ccc572f4ffe jdk/make/closed:d881e9ea30d3 jdk/src/closed:14bbe60b3dd0 langtools:34ee52bc68a4 nashorn:7efd6152328e"
        |BUILD_TYPE="commercial"
        |""".stripMargin
    val is = new ByteArrayInputStream(release.getBytes(StandardCharsets.UTF_8))
    val result = SlugParser.extractJdkVersion(is)
    result shouldBe Some("1.8.0_172")
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

  val pomParent = """<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://maven.apache.org/POM/4.0.0">
                    |
                    |  <modelVersion>4.0.0</modelVersion>
                    |
                    |  <parent>
                    |    <groupId>org.slf4j</groupId>
                    |    <artifactId>slf4j-parent</artifactId>
                    |    <version>1.7.25</version>
                    |  </parent>
                    |
                    |  <artifactId>jul-to-slf4j</artifactId>
                    |
                    |  <packaging>jar</packaging>
                    |  <name>JUL to SLF4J bridge</name>
                    |  <description>JUL to SLF4J bridge</description>
                    |
                    |  <url>http://www.slf4j.org</url>
                    |
                    |  <dependencies>
                    |    <dependency>
                    |      <groupId>org.slf4j</groupId>
                    |      <artifactId>slf4j-api</artifactId>
                    |    </dependency>
                    |    <dependency>
                    |      <groupId>org.slf4j</groupId>
                    |      <artifactId>slf4j-log4j12</artifactId>
                    |      <version>1.0.0</version>
                    |      <scope>test</scope>
                    |    </dependency>
                    |  </dependencies>
                    |
                    |</project>""".stripMargin
}
