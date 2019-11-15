import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport._

  val bootstrapPlayVersion = "1.1.0"
  val hmrcMongoVersion     = "0.8.0"

  val compile = Seq(
    ws,
    ehcache,
    "uk.gov.hmrc"        %% "bootstrap-play-26"         % bootstrapPlayVersion,
    "uk.gov.hmrc"        %% "github-client"             % "2.10.0",
    "uk.gov.hmrc"        %% "hmrc-mongo-metrix-play-26" % hmrcMongoVersion,
    "uk.gov.hmrc"        %% "hmrc-mongo-play-26"        % hmrcMongoVersion,
    "org.typelevel"      %% "cats-core"                 % "1.1.0",
    "org.apache.commons" % "commons-compress"           % "1.19",
    "com.lightbend.akka" %% "akka-stream-alpakka-sns"   % "1.1.2",
    "com.lightbend.akka" %% "akka-stream-alpakka-sqs"   % "1.1.2"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-play-26"  % bootstrapPlayVersion % Test classifier "tests",
    "uk.gov.hmrc"            %% "hmrctest"           % "3.3.0"              % Test,
    "org.scalatest"          %% "scalatest"          % "3.0.8"              % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2"              % Test,
    "uk.gov.hmrc"            %% "hmrc-mongo-test"    % hmrcMongoVersion     % Test,
    "org.mockito"            %% "mockito-scala"      % "1.7.1"              % Test,
    "org.pegdown"            % "pegdown"             % "1.6.0"              % Test, //pegdown dependency needed by scalatest, until this PR is released (v 3.1.0): https://github.com/scalatest/scalatest/pull/1229
    "com.typesafe.play"      %% "play-test"          % PlayVersion.current  % Test,
    "com.github.tomakehurst" % "wiremock"            % "1.58"               % Test,
    "com.typesafe.akka"      %% "akka-testkit"       % "2.5.16"             % Test,
    // force dependencies due to security flaws found in xercesImpl 2.11.0
    "xerces"                 % "xercesImpl"          % "2.12.0"             % Test
  )
}
