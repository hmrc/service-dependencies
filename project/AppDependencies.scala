import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport._

  val bootstrapPlayVersion = "1.1.0"

  val compile = Seq(
    ws,
    ehcache,
    "uk.gov.hmrc"        %% "bootstrap-play-26"       % bootstrapPlayVersion,
    "uk.gov.hmrc"        %% "github-client"           % "2.10.0",
    "uk.gov.hmrc"        %% "metrix"                  % "3.8.0-play-26",
    "uk.gov.hmrc"        %% "simple-reactivemongo"    % "7.20.0-play-26",
    "uk.gov.hmrc"        %% "mongo-lock"              % "6.15.0-play-26",
    "com.typesafe.play"  %% "play-json-joda"          % "2.6.0",
    "org.typelevel"      %% "cats-core"               % "1.1.0",
    "org.apache.commons" % "commons-compress"         % "1.19",
    "com.lightbend.akka" %% "akka-stream-alpakka-sns" % "1.1.2",
    "com.lightbend.akka" %% "akka-stream-alpakka-sqs" % "1.1.2"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-play-26"  % bootstrapPlayVersion % Test classifier "tests",
    "uk.gov.hmrc"            %% "hmrctest"           % "3.3.0"              % Test,
    "org.scalatest"          %% "scalatest"          % "3.0.8"              % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2"              % Test,
    "uk.gov.hmrc"            %% "reactivemongo-test" % "4.15.0-play-26"     % "test",
    "org.mockito"            % "mockito-core"        % "2.28.2"             % Test,
    "org.pegdown"            % "pegdown"             % "1.6.0"              % Test,
    "com.typesafe.play"      %% "play-test"          % PlayVersion.current  % Test,
    "com.github.tomakehurst" % "wiremock"            % "1.58"               % Test,
    "com.typesafe.akka"      %% "akka-testkit"       % "2.5.16"             % Test,
    // force dependencies due to security flaws found in xercesImpl 2.11.0
    "xerces" % "xercesImpl" % "2.12.0" % Test
  )
}
