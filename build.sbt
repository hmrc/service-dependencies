import play.sbt.PlayImport.PlayKeys
import play.sbt.routes.RoutesKeys

lazy val microservice = Project("service-dependencies", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(majorVersion := 2)
  .settings(PlayKeys.playDefaultPort := 8459)
  .settings(libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test)
  .settings(scalaVersion := "3.3.6")
  .settings(javaOptions += "-Xmx2G")
  .settings(scalacOptions ++= Seq(
    "-Wconf:src=routes/.*:s"
  , "-Wconf:msg=Flag.*repeatedly:s"
  ))
  .settings(
    Test / resources := (Test / resources).value ++ Seq(baseDirectory.value / "conf" / "application.conf")
  )
  .settings(
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.servicedependencies.binders.Binders.given"
    , "uk.gov.hmrc.servicedependencies.model._"
    )
  )
