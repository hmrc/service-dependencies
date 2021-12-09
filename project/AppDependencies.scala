import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport.{ehcache, ws}

  val bootstrapPlayVersion = "5.16.0"
  val hmrcMongoVersion     = "0.55.0"

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
    // akka-stream-alpakka-sns depends on 10.1.11 which isn't compatible with play's akka version
    "com.typesafe.akka"  %% "akka-http"                 % PlayVersion.akkaHttpVersion,
    "org.yaml"           %  "snakeyaml"                 % "1.27"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28" % hmrcMongoVersion     % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.16.46"            % Test,
    "com.typesafe.akka"      %% "akka-testkit"            % PlayVersion.akkaVersion % Test
  )
}
