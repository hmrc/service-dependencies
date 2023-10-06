import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport.{ehcache, ws}

  val bootstrapPlayVersion = "7.22.0"
  val hmrcMongoVersion     = "1.3.0"

  val compile = Seq(
    ws,
    ehcache,
    "uk.gov.hmrc"            %% "bootstrap-backend-play-28" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-28"        % hmrcMongoVersion,
    "org.typelevel"          %% "cats-core"                 % "2.10.0",
    "org.apache.commons"     %  "commons-compress"          % "1.20",
    "software.amazon.awssdk" %  "sqs"                       % "2.20.155",
    "org.yaml"               %  "snakeyaml"                 % "1.33"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28" % hmrcMongoVersion     % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.16.46"            % Test,
    "com.typesafe.akka"      %% "akka-testkit"            % PlayVersion.akkaVersion % Test
  )
}
