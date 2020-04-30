import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport._

  val bootstrapPlayVersion = "2.6.0"
  val hmrcMongoVersion     = "0.28.0"

  val compile = Seq(
    ws,
    ehcache,
    "uk.gov.hmrc"        %% "bootstrap-backend-play-27" % bootstrapPlayVersion,
    "uk.gov.hmrc"        %% "github-client"             % "2.11.0",
    "uk.gov.hmrc.mongo"  %% "hmrc-mongo-metrix-play-27" % hmrcMongoVersion,
    "uk.gov.hmrc.mongo"  %% "hmrc-mongo-play-27"        % hmrcMongoVersion,
    "org.typelevel"      %% "cats-core"                 % "2.1.1",
    "org.apache.commons" %  "commons-compress"          % "1.19",
    "com.lightbend.akka" %% "akka-stream-alpakka-sns"   % "1.1.2",
    "com.lightbend.akka" %% "akka-stream-alpakka-sqs"   % "1.1.2",
    // akka-stream-alpakka-sns depends on 10.1.10 which isn't compatible with play's akka version 10.1.11
    "com.typesafe.akka"  %% "akka-http"                 % "10.1.11"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-27"  % bootstrapPlayVersion % Test,
    "org.scalatest"          %% "scalatest"               % "3.1.0"              % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"      % "3.1.3"              % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-27" % hmrcMongoVersion     % Test,
    "org.mockito"            %% "mockito-scala"           % "1.10.2"             % Test,
    "com.typesafe.play"      %% "play-test"               % PlayVersion.current  % Test,
    "com.github.tomakehurst" %  "wiremock"                % "1.58"               % Test,
    "com.typesafe.akka"      %% "akka-testkit"            % "2.5.26"             % Test
  )
}
