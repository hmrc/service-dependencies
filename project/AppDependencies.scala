import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport.{ehcache, ws}

  val bootstrapPlayVersion = "7.14.0"
  val hmrcMongoVersion     = "1.1.0"

  val compile = Seq(
    ws,
    ehcache,
    "uk.gov.hmrc"        %% "bootstrap-backend-play-28" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"  %% "hmrc-mongo-play-28"        % hmrcMongoVersion,
    "org.typelevel"      %% "cats-core"                 % "2.6.1",
    "org.apache.commons" %  "commons-compress"          % "1.20",
    "com.lightbend.akka" %% "akka-stream-alpakka-sns"   % "4.0.0",
    "com.lightbend.akka" %% "akka-stream-alpakka-sqs"   % "4.0.0",
    // ensure akka-stream-alpakka-sns dependencies don't conflict with play's akka version
    "com.typesafe.akka"  %% "akka-http"                 % PlayVersion.akkaHttpVersion,
    "org.yaml"           %  "snakeyaml"                 % "1.33"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28" % hmrcMongoVersion     % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.16.46"            % Test,
    "com.typesafe.akka"      %% "akka-testkit"            % PlayVersion.akkaVersion % Test
  )
}
