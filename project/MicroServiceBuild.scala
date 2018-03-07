import sbt._
import uk.gov.hmrc.SbtAutoBuildPlugin
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin
import uk.gov.hmrc.versioning.SbtGitVersioning

object MicroServiceBuild extends Build with MicroService {
  override val appName                             = "service-dependencies"
  override lazy val appDependencies: Seq[ModuleID] = AppDependencies()
}

private object AppDependencies {
  import play.sbt.PlayImport._
  import play.core.PlayVersion

  val compile = Seq(
    ws,
    "uk.gov.hmrc"   %% "bootstrap-play-25"  % "1.4.0",
    "uk.gov.hmrc"   %% "play-url-binders"   % "2.1.0",
    "uk.gov.hmrc"   %% "github-client"      % "1.21.0",
    "uk.gov.hmrc"   %% "play-reactivemongo" % "6.0.0",
    "uk.gov.hmrc"   %% "mongo-lock"         % "5.1.0",
    "uk.gov.hmrc"   %% "time"               % "3.1.0",
    "org.typelevel" %% "cats"               % "0.9.0"
  )

  trait TestDependencies {
    lazy val scope: String       = "test"
    lazy val test: Seq[ModuleID] = ???
  }

  object Test {
    def apply() =
      new TestDependencies {
        override lazy val test = Seq(
          "uk.gov.hmrc"            %% "hmrctest"           % "3.0.0"             % scope,
          "org.scalatest"          %% "scalatest"          % "2.2.6"             % scope,
          "uk.gov.hmrc"            %% "reactivemongo-test" % "3.1.0"             % scope,
          "org.mockito"            % "mockito-core"        % "2.2.6"             % scope,
          "org.pegdown"            % "pegdown"             % "1.6.0"             % scope,
          "com.typesafe.play"      %% "play-test"          % PlayVersion.current % scope,
          "com.github.tomakehurst" % "wiremock"            % "1.55"              % scope,
          "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.1"             % scope
        )
      }.test
  }

  def apply() = compile ++ Test()
}
