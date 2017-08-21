/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.model

import java.util.Date

import play.api.libs.json.Json

case class LibraryDependency(libraryName: String, currentVersion:Version)
object LibraryDependency {
  implicit val format = Json.format[LibraryDependency]
}

case class SbtPluginDependency(sbtPluginName: String, currentVersion:Version)
object SbtPluginDependency {
  implicit val format = Json.format[SbtPluginDependency]
}

case class OtherDependency(name: String, currentVersion:Version)
object OtherDependency {
  implicit val format = Json.format[OtherDependency]
}


case class MongoRepositoryDependencies(repositoryName: String,
                                       libraryDependencies: Seq[LibraryDependency],
                                       sbtPluginDependencies: Seq[SbtPluginDependency] = Nil,
                                       otherDependencies: Seq[OtherDependency],
                                       updateDate: Long = new Date().getTime)
object MongoRepositoryDependencies {

  implicit val format = Json.format[MongoRepositoryDependencies]
}



