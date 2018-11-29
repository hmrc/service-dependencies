package uk.gov.hmrc.servicedependencies.connector.model


import org.joda.time.DateTime
import play.api.libs.json._

case class RepositoryInfo(name:String, createdAt: DateTime, lastUpdatedAt:DateTime, repoType:String= "Other", language:Option[String] = None)

object RepositoryInfo {

  val pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
  implicit val dateFormat: Format[DateTime] = Format[DateTime](JodaReads.jodaDateReads(pattern), JodaWrites.jodaDateWrites(pattern))

  implicit val format: OFormat[RepositoryInfo] = Json.using[Json.WithDefaultValues].format[RepositoryInfo]

}
