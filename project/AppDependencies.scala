import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport._

  val bootstrapPlayVersion = "5.7.0"
  val hmrcMongoVersion     = "0.51.0"

  val compile = Seq(
    ws,
    ehcache,
    "uk.gov.hmrc"        %% "bootstrap-backend-play-28" % bootstrapPlayVersion,
    "uk.gov.hmrc"        %% "github-client"             % "3.0.0",
    "uk.gov.hmrc.mongo"  %% "hmrc-mongo-play-28"        % hmrcMongoVersion,
    "org.typelevel"      %% "cats-core"                 % "2.6.1",
    "org.apache.commons" %  "commons-compress"          % "1.20",
    "com.lightbend.akka" %% "akka-stream-alpakka-sns"   % "2.0.2",
    "com.lightbend.akka" %% "akka-stream-alpakka-sqs"   % "2.0.2",
    // akka-stream-alpakka-sns depends on 10.1.11 which isn't compatible with play's akka version 10.1.13
    "com.typesafe.akka"  %% "akka-http"                 % "10.1.13",
    "org.yaml"           %  "snakeyaml"                 % "1.27"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28" % hmrcMongoVersion     % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.16.23"            % Test,
    "com.typesafe.akka"      %% "akka-testkit"            % "2.6.10"             % Test
  )
}
