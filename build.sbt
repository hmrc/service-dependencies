import play.sbt.PlayImport.PlayKeys.devSettings
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

val appName = "service-dependencies"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(
    Seq(play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory): _*)
  .settings(majorVersion := 1)
  .settings(publishingSettings: _*)
  .settings(devSettings := Seq("play.server.netty.maxInitialLineLength" -> "65536"))
  .settings(PlayKeys.playDefaultPort := 8459)
  .settings(libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test)
  .settings(resolvers += Resolver.jcenterRepo)
  // TODO remove this once hmrc-mongo is distributed to bintray
  .settings(resolvers += "HMRC Artifactory releases" at "https://artefacts.tax.service.gov.uk/artifactory/hmrc-releases/")
  .settings(scalaVersion := "2.11.12")
