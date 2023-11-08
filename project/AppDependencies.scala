import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport.{ehcache, ws}

  val bootstrapPlayVersion = "8.0.0"
  val hmrcMongoVersion     = "1.5.0"

  val compile = Seq(
    ws,
    ehcache,
    "uk.gov.hmrc"            %% "bootstrap-backend-play-30" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-30"        % hmrcMongoVersion,
    "org.typelevel"          %% "cats-core"                 % "2.10.0",
    "org.apache.commons"     %  "commons-compress"          % "1.20",
    "software.amazon.awssdk" %  "sqs"                       % "2.20.155",
    "org.yaml"               %  "snakeyaml"                 % "2.1"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion     % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.17.14"            % Test
  )
}
