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

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, JsValue, __}

enum ScalaVersion(val asString: String, val asClassifier: String):
  case SV_None extends ScalaVersion(asString = "none", asClassifier = ""           )
  case SV_2_11 extends ScalaVersion(asString = "2.11", asClassifier = s"_2.11")
  case SV_2_12 extends ScalaVersion(asString = "2.12", asClassifier = s"_2.12")
  case SV_2_13 extends ScalaVersion(asString = "2.13", asClassifier = s"_2.13")
  case SV_3    extends ScalaVersion(asString = "3"   , asClassifier = s"_3")

object ScalaVersion:
  given Ordering[ScalaVersion] with
    def compare(x: ScalaVersion, y: ScalaVersion): Int =
      values.indexOf(x).compare(values.indexOf(y))

  def parse(s: String): Option[ScalaVersion] =
    values.find(_.asString == s)

  val format: Format[ScalaVersion] =
    new Format[ScalaVersion]:
      override def reads(json: JsValue) =
        json.validate[String]
          .flatMap: s =>
            parse(s) match
              case Some(env) => JsSuccess(env)
              case None      => JsError(__, s"Unsupported Scala Version '$s'")

      override def writes(e: ScalaVersion) =
        JsString(e.asString)
