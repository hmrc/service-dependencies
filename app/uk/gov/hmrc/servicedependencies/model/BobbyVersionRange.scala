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

import play.api.libs.json._

// TODO rename as VersionRange?
/** Iso to Either[Qualifier, (Option[LowerBound], Option[UpperBound])]*/
case class BobbyVersionRange(
    lowerBound: Option[BobbyVersion]
  , upperBound: Option[BobbyVersion]
  , qualifier : Option[String]
  , range     : String
  ) {

  def includes(v: Version): Boolean = {
    val qualFilter: Function1[Version, Boolean] = qualifier match {
      case Some(qual) => _.toString.contains(qual)
      case None       => _ => true
    }
    val lbFilter: Function1[Version, Boolean] = lowerBound match {
      case Some(BobbyVersion(version, true))  => _.normalise >= version
      case Some(BobbyVersion(version, false)) => _.normalise >  version
      case None                               => _ => true
    }
    val ubFilter: Function1[Version, Boolean] = upperBound match {
      case Some(BobbyVersion(version, true))  => _.normalise <= version
      case Some(BobbyVersion(version, false)) => _.normalise <  version
      case None                               => _ => true
    }
    qualFilter(v) && lbFilter(v) && ubFilter(v)
  }

  override def toString: String = range
}

object BobbyVersionRange {

  private val fixed      = """^\[(\d+\.\d+.\d+)\]""".r
  private val fixedUpper = """^[\[\(],?(\d+\.\d+.\d+)[\]\)]""".r
  private val fixedLower = """^[\[\(](\d+\.\d+.\d+),[\]\)]""".r
  private val rangeRegex = """^[\[\(](\d+\.\d+.\d+),(\d+\.\d+.\d+)[\]\)]""".r
  private val qualifier  = """^\[[-\*]+(.*)\]""".r

  def parse(range: String): Option[BobbyVersionRange] = {
    val trimmedRange = range.replaceAll(" ", "")

    PartialFunction.condOpt(trimmedRange) {
      case fixed(v) =>
        val fixed = BobbyVersion(Version(v), inclusive = true)
        BobbyVersionRange(
            lowerBound = Some(fixed)
          , upperBound = Some(fixed)
          , qualifier  = None
          , range      = trimmedRange
          )
      case fixedUpper(v) =>
        val ub = BobbyVersion(Version(v), inclusive = trimmedRange.endsWith("]"))
        BobbyVersionRange(
            lowerBound = None
          , upperBound = Some(ub)
          , qualifier  = None
          , range      = trimmedRange
          )
      case fixedLower(v) =>
        val lb = BobbyVersion(Version(v), inclusive = trimmedRange.startsWith("["))
        BobbyVersionRange(
            lowerBound = Some(lb)
          , upperBound = None
          , qualifier  = None
          , range      = trimmedRange
          )
      case rangeRegex(v1, v2) =>
        val lb = BobbyVersion(Version(v1), inclusive = trimmedRange.startsWith("["))
        val ub = BobbyVersion(Version(v2), inclusive = trimmedRange.endsWith("]"))
        BobbyVersionRange(
            lowerBound = Some(lb)
          , upperBound = Some(ub)
          , qualifier  = None
          , range      = trimmedRange
          )
      case qualifier(q) if q.length > 1 =>
        BobbyVersionRange(
            lowerBound = None
          , upperBound = None
          , qualifier  = Some(q)
          , range      = trimmedRange
          )
    }
  }

  def apply(range: String): BobbyVersionRange =
    parse(range).getOrElse(sys.error(s"Could not parse range $range"))

  val format: Format[BobbyVersionRange] = new Format[BobbyVersionRange] {
    override def reads(json: JsValue) =
      json match {
        case JsString(s) => parse(s).map(v => JsSuccess(v)).getOrElse(JsError("Could not parse range"))
        case _           => JsError("Not a string")
      }

    override def writes(bvr: BobbyVersionRange) =
      JsString(bvr.range)
  }
}
