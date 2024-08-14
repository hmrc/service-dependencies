import sbt._

private object AppDependencies {
  import play.core.PlayVersion
  import play.sbt.PlayImport.{caffeine, ws}

  val bootstrapPlayVersion = "9.3.0"
  val hmrcMongoVersion     = "2.2.0"

  val compile = Seq(
    ws,
    caffeine,
    "uk.gov.hmrc"            %% "bootstrap-backend-play-30" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-30"        % hmrcMongoVersion,
    "org.typelevel"          %% "cats-core"                 % "2.10.0",
    "org.apache.commons"     %  "commons-compress"          % "1.20",
    "software.amazon.awssdk" %  "sqs"                       % "2.27.3"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion     % Test,
    "org.scalacheck"         %% "scalacheck"              % "1.17.0"             % Test
  )
}
