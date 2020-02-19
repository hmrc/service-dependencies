/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.util

import uk.gov.hmrc.servicedependencies.model.Version

object VersionParser {
  def parsePropertyFile(contents: String, key: String): Option[Version] = {
    val propertyRegex = ("""\s*""" + key.replaceAll("\\.", "\\\\.") + """\s*=\s*(\d+\.\d+\.\d+)\s*""").r.unanchored
    contents match {
      case propertyRegex(version) => Version.parse(version.replaceAll("\"", ""))
      case _                      => None
    }
  }

  def parse(fileContent: String, artifacts: Seq[(String, String)]): Map[(String, String), Option[Version]] =
    artifacts
      .map { case (name, group) =>
        (name, group) -> parse(fileContent = fileContent, name = name, group = group)
       }
      .toMap

  def parse(fileContent: String, name: String, group: String): Option[Version] = {
    val stringVersion   = ("\"" + group + "\"[\\s%]*\"" + name + "\"" + """\s*%\s*"(\d+\.\d+\.\d+-?\S*)"""").r.unanchored
    val variableVersion = ("\"" + group + "\"[\\s%]*\"" + name + "\"" + """\s*%\s*(\w*)""").r.unanchored
    fileContent match {
      case stringVersion(version)    => Version.parse(version)
      case variableVersion(variable) => extractVersionInVariable(fileContent, variable)
      case _                         => None
    }
  }

  private def extractVersionInVariable(file: String, variable: String): Option[Version] = {
    val variableRegex = (variable + """\s*=\s*"(\d+\.\d+\.\d+-?\S*)"""").r.unanchored
    file match {
      case variableRegex(value) => Version.parse(value)
      case _                    => None
    }
  }
}
