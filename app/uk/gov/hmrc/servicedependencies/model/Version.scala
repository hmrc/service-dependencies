/*
 * Copyright 2019 HM Revenue & Customs
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

import play.api.libs.json.Json

import play.api.libs.functional.syntax._

case class Version(
    major   : Int,
    minor   : Int,
    patch   : Int,
    original: String)
  extends Ordered[Version] {

  override def compare(other: Version): Int =
    if (major == other.major)
      if (minor == other.minor)
        patch - other.patch
      else
        minor - other.minor
    else
      major - other.major

  override def toString: String = original
  def normalise                 = s"${major}_${minor}_$patch"
}

object Version {
  implicit val format = Json.format[Version]

  def apply(version: String): Version =
    parse(version).getOrElse(sys.error(s"Could not parse version $version"))

  def apply(major: Int, minor: Int, patch: Int): Version =
    Version(major, minor, patch, s"$major.$minor.$patch")

  def parse(s: String): Option[Version] = {
    val regex3 = """(\d+)\.(\d+)\.(\d+)?(.*)""".r.unanchored
    val regex2 = """(\d+)\.(\d+)?(.*)""".r.unanchored
    val regex1 = """(\d+)?(.*)""".r.unanchored
    s match {
      case regex3(maj, min, patch, _) => Some(Version(Integer.parseInt(maj), Integer.parseInt(min), Integer.parseInt(patch), s))
      case regex2(maj, min,  _)       => Some(Version(Integer.parseInt(maj), Integer.parseInt(min), 0                      , s))
      case regex1(min,  _)            => Some(Version(0                    , Integer.parseInt(min), 0                      , s))
      case _                          => None
    }
  }

  implicit class VersionExtensions(v: String) {
    def asVersion(): Version =
      Version(v)
  }
}

trait VersionOp
object VersionOp {
  case object Gt extends VersionOp
  case object Lt extends VersionOp
  case object Eq extends VersionOp

  def parse(s: String): Option[VersionOp] =
    s match {
      case "gt" => Some(Gt)
      case "lt" => Some(Lt)
      case "eq" => Some(Eq)
      case _    => None
    }
}