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

package uk.gov.hmrc.servicedependencies

object BuildFileVersionParser {

  def parse(file: String, artifact: String): Option[Version] = {

    val stringVersion = ("\"" + artifact + "\"" + """\s*%\s*("\d+\.\d+\.\d+")""").r.unanchored
    val variableVersion = ("\"" + artifact + "\"" + """\s*%\s*(\w*)""").r.unanchored

    file match {
      case stringVersion(version) => Some(Version.parse(version.replaceAll("\"", "")))
      case variableVersion(variable) => extractVersionInVariable(file, variable)
      case _ => None
    }
  }

  private def extractVersionInVariable(file: String, variable: String): Option[Version] = {
    val variableRegex = (variable + """\s*=\s*("\d+\.\d+\.\d+")""").r.unanchored
    file match {
      case variableRegex(value) => Some(Version.parse(value.replaceAll("\"", "")))
      case _ => None
    }
  }
}

object PluginsSbtFileVersionParser {

  def parse(file: String, artifact: String): Option[Version] = {
    val stringVersion = (s""".*"$artifact""" + """"\s*%\s*"(\d+\.\d+\.\d+)".*""").r.unanchored

    file match {
      case stringVersion(version) => Some(Version.parse(version))
      case _ => None
    }
  }
}
