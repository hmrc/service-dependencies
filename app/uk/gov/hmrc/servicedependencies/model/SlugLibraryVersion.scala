package uk.gov.hmrc.servicedependencies.model
import play.api.libs.json.{Json, OFormat}

case class SlugLibraryVersion(slugName: String, libraryName: String, version: String)

case class SlugDependencyInfo(slugName: String, slugUri: String, dependencies: Seq[SlugLibraryVersion])

object SlugLibraryVersion {
  implicit val format: OFormat[SlugLibraryVersion] = Json.format[SlugLibraryVersion]
}

object SlugDependencyInfo {
  implicit val format: OFormat[SlugDependencyInfo] = Json.format[SlugDependencyInfo]
}

