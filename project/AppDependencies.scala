import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport._

  val bootstrapPlayVersion = "0.23.0"

  val compile = Seq(
    ws,
    "uk.gov.hmrc"       %% "bootstrap-play-26"    % bootstrapPlayVersion,
    "uk.gov.hmrc"       %% "github-client"        % "2.5.0",
    "uk.gov.hmrc"       %% "metrix"               % "3.5.0-play-26",
    "uk.gov.hmrc"       %% "simple-reactivemongo" % "7.9.0-play-26",
    "uk.gov.hmrc"       %% "mongo-lock"           % "6.6.0-play-26",
    "com.typesafe.play" %% "play-json-joda"       % "2.6.0",
    "org.typelevel"     %% "cats"                 % "0.9.0",
    "org.apache.commons" % "commons-compress"     % "1.18"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-play-26"  % bootstrapPlayVersion % Test classifier "tests",
    "uk.gov.hmrc"            %% "hmrctest"           % "3.2.0"              % Test,
    "org.scalatest"          %% "scalatest"          % "3.0.4"              % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2"              % Test,
    "uk.gov.hmrc"            %% "reactivemongo-test" % "4.6.0-play-26"      % Test,
    "org.mockito"            %  "mockito-core"       % "2.2.6"              % Test,
    "org.pegdown"            %  "pegdown"            % "1.6.0"              % Test,
    "com.typesafe.play"      %% "play-test"          % PlayVersion.current  % Test,
    "com.github.tomakehurst" %  "wiremock"           % "1.55"               % Test,
    "com.typesafe.akka"      %% "akka-testkit"       % "2.5.16"             % Test
  )
}
