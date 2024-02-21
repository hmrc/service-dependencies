import play.sbt.PlayImport.PlayKeys
import play.sbt.routes.RoutesKeys

lazy val microservice = Project("service-dependencies", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(majorVersion := 2)
  .settings(PlayKeys.devSettings += "play.server.netty.maxInitialLineLength" -> "65536")
  .settings(PlayKeys.playDefaultPort := 8459)
  .settings(libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(scalaVersion := "2.13.12")
  .settings(scalacOptions += "-Wconf:src=routes/.*:s")
  .settings(
    Test / resources := (Test / resources).value ++ Seq(baseDirectory.value / "conf" / "application.conf")
  )
  .settings(
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.servicedependencies.binders.Binders._",
      "uk.gov.hmrc.servicedependencies.model._"
    )
  )
