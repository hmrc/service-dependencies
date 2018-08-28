import sbt._


private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport._

  val compile = Seq(
    ws,
    "uk.gov.hmrc"   %% "bootstrap-play-25"  % "1.7.0",
    "uk.gov.hmrc"   %% "play-url-binders"   % "2.1.0",
    "uk.gov.hmrc"   %% "github-client"      % "1.21.0",
    "uk.gov.hmrc"   %% "play-reactivemongo" % "6.2.0",
    "uk.gov.hmrc"   %% "metrix"             % "2.0.0",
    "uk.gov.hmrc"   %% "mongo-lock"         % "5.1.0",
    "uk.gov.hmrc"   %% "time"               % "3.1.0",
    "org.typelevel" %% "cats"               % "0.9.0"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "hmrctest"           % "3.0.0"             % "test",
    "org.scalatest"          %% "scalatest"          % "2.2.6"             % "test",
    "uk.gov.hmrc"            %% "reactivemongo-test" % "3.1.0"             % "test",
    "org.mockito"            % "mockito-core"        % "2.2.6"             % "test",
    "org.pegdown"            % "pegdown"             % "1.6.0"             % "test",
    "com.typesafe.play"      %% "play-test"          % PlayVersion.current % "test",
    "com.github.tomakehurst" % "wiremock"            % "1.55"              % "test",
    "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.1"             % "test"
  )
}
