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

import play.api.libs.json.{JsPath, Json, Reads}

import play.api.libs.functional.syntax._



case class Version(major: Int, minor: Int, patch: Int) {
  def <(other: Version) = {
    if (major == other.major)
      if (minor == other.minor)
        patch < other.patch
      else
        minor < other.minor
    else
      major < other.major
  }

  override def toString: String = s"$major.$minor.$patch"
}

object Version {
  implicit val format = Json.format[Version]


  def apply(version: String): Version =
    parse(version)

  def parse(s: String) = {
    val split = s.split("\\.")
    Version(Integer.parseInt(split(0)),
    Integer.parseInt(split(1)),
    Integer.parseInt(split(2)))
  }

  implicit val ord = Ordering.by(unapply)

  implicit class VersionExtensions(v: String) {
    def asVersion(): Version = {
      Version(v)
    }
  }
}
