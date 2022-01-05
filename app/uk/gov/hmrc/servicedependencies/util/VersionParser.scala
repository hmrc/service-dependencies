/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.servicedependencies.connector.GithubDependency

object VersionParser {
  def parsePropertyFile(contents: String, key: String): Option[Version] = {
    val propertyRegex = ("""\s*""" + key.replaceAll("\\.", "\\\\.") + """\s*=\s*(\d+\.\d+\.\d+)\s*""").r.unanchored
    contents match {
      case propertyRegex(version) => Some(Version(version.replaceAll("\"", "")))
      case _                      => None
    }
  }

  def parse(fileContent: String): Seq[GithubDependency] = {
    val stringVersion   = (""""([^"]+)"[\s%]*"([^"]+)"\s*%\s*"([^"]*)"""").r.unanchored
    val variableVersion = (""""([^"]+)"[\s%]*"([^"]+)"\s*%\s*(\w+)""").r.unanchored

    val stringMatches =
      stringVersion.findAllMatchIn(fileContent).map { m =>
        GithubDependency(group = m.group(1), name = m.group(2), version = Version(m.group(3)))
      }.toSeq

    val variableMatches =
      variableVersion.findAllMatchIn(fileContent).map { m =>
        extractVersionInVariable(fileContent, m.group(3)).map { v =>
          GithubDependency(group = m.group(1), name = m.group(2), version = v)
        }
      }.flatten.toSeq

    stringMatches ++ variableMatches
  }

  private def extractVersionInVariable(file: String, variable: String): Option[Version] = {
    val variableRegex = (variable + """\s*=\s*"(\d+\.\d+\.\d+-?\S*)"""").r.unanchored
    file match {
      case variableRegex(value) => Some(Version(value))
      case _                    => None
    }
  }
}
