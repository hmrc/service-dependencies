/*
 * Copyright 2023 HM Revenue & Customs
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

import play.api.libs.json.{__, Format, OFormat, Writes}
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
        if (patch == other.patch)
          other.original.length - original.length  // prefer pure semantic version (e.g. 1.0.0 > 1.0.0-SNAPSHOT)
        else
          patch - other.patch
      else
        minor - other.minor
    else
      major - other.major

  override def toString: String =
    original

  def normalise: Version =
    Version(major, minor, patch, original = s"$major.$minor.$patch")

  def isReleaseCandidate: Boolean =
    original.endsWith("-RC")
}

object Version {
  val format: Format[Version] =
    implicitly[Format[String]].inmap(Version.apply, _.toString)

  // for backward compatibility - non-catalogue apis require broken down version
  val legacyApiWrites: Writes[Version] =
    ( (__ \ "major"   ).write[Int]
    ~ (__ \ "minor"   ).write[Int]
    ~ (__ \ "patch"   ).write[Int]
    ~ (__ \ "original").write[String]
    )(v => (v.major, v.minor, v.patch, v.original))

  val mongoVersionRepositoryFormat:OFormat[Version] = (__ \ "version" ).format[Version](format)

  def apply(version: String): Version = {
    val regex3 = """(\d+)\.(\d+)\.(\d+)(.*)""".r
    val regex2 = """(\d+)\.(\d+)(.*)""".r
    val regex1 = """(\d+)(.*)""".r
    version match {
      case regex3(maj, min, patch, _) => Version(Integer.parseInt(maj), Integer.parseInt(min), Integer.parseInt(patch), version)
      case regex2(maj, min,  _)       => Version(Integer.parseInt(maj), Integer.parseInt(min), 0                      , version)
      case regex1(patch,  _)          => Version(0                    , 0                    , Integer.parseInt(patch), version)
      case _                          => Version(0                    , 0                    , 0                      , version)
    }
  }

  def apply(major: Int, minor: Int, patch: Int): Version =
    Version(major, minor, patch, s"$major.$minor.$patch")
}
