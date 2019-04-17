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

package uk.gov.hmrc.servicedependencies.connector.model
import play.api.libs.json._
import uk.gov.hmrc.servicedependencies.model.Version

final case class BobbyVersionRange(
  lowerBound: Option[BobbyVersion],
  upperBound: Option[BobbyVersion],
  qualifier: Option[String],
  range: String) {

  private def predicate(version1: Version, version2: Version, inclusive: Boolean): Boolean =
    version1.normalise < version2.normalise || (inclusive && version1.normalise == version2.normalise)

  def includes(version: Version): Boolean = this match {
    case BobbyVersionRange(_, _, Some(qual), _) => version.toString.contains(qual)
    case BobbyVersionRange(Some(lower), Some(upper), _, _) =>
      predicate(version, upper.version, upper.inclusive) && predicate(lower.version, version, lower.inclusive)
    case BobbyVersionRange(Some(lower), _, _, _) => predicate(lower.version, version, lower.inclusive)
    case BobbyVersionRange(_, Some(upper), _, _) => predicate(version, upper.version, upper.inclusive)
    case _                                       => false
  }

  override def toString: String = range
}

object BobbyVersionRange {

  private val fixed      = """^\[(\d+\.\d+.\d+)\]""".r
  private val fixedUpper = """^\(,?(\d+\.\d+.\d+)[\]\)]""".r
  private val fixedLower = """^[\[\(](\d+\.\d+.\d+),[\]\)]""".r
  private val rangeRegex = """^[\[\(](\d+\.\d+.\d+),(\d+\.\d+.\d+)[\]\)]""".r
  private val qualifier  = """^\[[-\*]+(.*)\]""".r

  val reads: Reads[BobbyVersionRange] = JsPath.read[String].map(BobbyVersionRange.apply)

  def apply(range: String): BobbyVersionRange = {
    val trimmedRange = range.replaceAll(" ", "")

    trimmedRange match {
      case fixed(v) =>
        val fixed = Version.parse(v).map(BobbyVersion(_, inclusive = true))
        BobbyVersionRange(lowerBound = fixed, upperBound = fixed, qualifier = None, range = trimmedRange)
      case fixedUpper(v) =>
        BobbyVersionRange(
          lowerBound = None,
          upperBound = Version.parse(v).map(BobbyVersion(_, inclusive = trimmedRange.endsWith("]"))),
          qualifier  = None,
          range      = trimmedRange)
      case fixedLower(v) =>
        BobbyVersionRange(
          lowerBound = Version.parse(v).map(BobbyVersion(_, inclusive = trimmedRange.startsWith("["))),
          upperBound = None,
          qualifier  = None,
          range      = trimmedRange)
      case rangeRegex(v1, v2) =>
        BobbyVersionRange(
          lowerBound = Version.parse(v1).map(BobbyVersion(_, inclusive = trimmedRange.startsWith("["))),
          upperBound = Version.parse(v2).map(BobbyVersion(_, inclusive = trimmedRange.endsWith("]"))),
          qualifier  = None,
          range      = trimmedRange
        )
      case qualifier(q) if q.length() > 1 =>
        BobbyVersionRange(lowerBound = None, upperBound = None, qualifier = Some(q), range = trimmedRange)
      case _ => BobbyVersionRange(lowerBound = None, upperBound = None, qualifier = None, range = trimmedRange)
    }
  }

}
