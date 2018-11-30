/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.servicedependencies.connector.model


import org.joda.time.DateTime
import play.api.libs.json._

case class RepositoryInfo(name:String, createdAt: DateTime, lastUpdatedAt:DateTime, repoType:String= "Other", language:Option[String] = None)

object RepositoryInfo {

  val pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
  implicit val dateFormat: Format[DateTime] = Format[DateTime](JodaReads.jodaDateReads(pattern), JodaWrites.jodaDateWrites(pattern))

  implicit val format: OFormat[RepositoryInfo] = Json.using[Json.WithDefaultValues].format[RepositoryInfo]

}
