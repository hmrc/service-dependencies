import sbt._
import uk.gov.hmrc.SbtAutoBuildPlugin
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin
import uk.gov.hmrc.versioning.SbtGitVersioning

object MicroServiceBuild extends Build with MicroService {
  override val appName = "service-dependencies"
  override lazy val appDependencies: Seq[ModuleID] = AppDependencies()
}

private object AppDependencies {
  import play.sbt.PlayImport._
  import play.core.PlayVersion

  private val microserviceBootstrapVersion = "6.8.0"
  private val playUrlBindersVersion = "2.1.0"
  private val hmrcTestVersion = "2.3.0"
  private val scalaTestVersion = "2.2.6"
  private val pegdownVersion = "1.6.0"
  private val githubClientVersion = "1.19.0"
  private val mockitoVersion = "2.2.6"
  private val wireMockVersion = "1.55"
  private val scalaTestPlusPlayVersion = "1.5.1"
  private val playReactivemongoVersion = "6.0.0"


  val compile = Seq(
    ws,
    "uk.gov.hmrc" %% "bootstrap-play-25" % "0.10.0",
    "uk.gov.hmrc" %% "play-url-binders" % playUrlBindersVersion,
    "uk.gov.hmrc" %% "github-client" % githubClientVersion,
    "uk.gov.hmrc" %% "play-reactivemongo" % playReactivemongoVersion,
    "uk.gov.hmrc" %% "mongo-lock" % "5.0.0",
    "org.typelevel" %% "cats" % "0.9.0"
  )

  trait TestDependencies {
    lazy val scope: String = "test"
    lazy val test : Seq[ModuleID] = ???
  }

  object Test {
    def apply() = new TestDependencies {
      override lazy val test = Seq(
        "uk.gov.hmrc" %% "hmrctest" % "2.4.0" % scope,
      "org.scalatest" %% "scalatest" % scalaTestVersion % scope,
        "uk.gov.hmrc" %% "reactivemongo-test" % "3.0.0" % scope,
        "org.mockito" % "mockito-core" % mockitoVersion % scope,
        "org.pegdown" % "pegdown" % pegdownVersion % scope,
        "com.typesafe.play" %% "play-test" % PlayVersion.current % scope,
        "com.github.tomakehurst" % "wiremock" % wireMockVersion % scope,
        "org.scalatestplus.play" %% "scalatestplus-play" % scalaTestPlusPlayVersion % scope
      )
    }.test
  }

  def apply() = compile ++ Test()
}