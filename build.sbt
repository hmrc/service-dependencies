import play.sbt.PlayImport.PlayKeys
import play.sbt.routes.RoutesKeys

ThisBuild / scalacOptions += "-Wconf:msg=Flag.*repeatedly:s"

lazy val microservice = Project("service-dependencies", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    majorVersion := 2,
    scalaVersion := "3.3.6",
    PlayKeys.playDefaultPort := 8459,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    Compile / javaOptions += "-Xmx2G", // without `Compile` it breaks sbt start/runProd (Universal scope)
    scalacOptions += "-Wconf:src=routes/.*:s"
  )
  .settings(
    Test / resources := (Test / resources).value ++ Seq(baseDirectory.value / "conf" / "application.conf")
  )
  .settings(
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.servicedependencies.binders.Binders.given"
    , "uk.gov.hmrc.servicedependencies.model._"
    )
  )
