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

package uk.gov.hmrc.servicedependencies.model

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, JsValue, __}

sealed trait ScalaVersion { def asString: String; def asClassifier: String }
case object ScalaVersion {
  case object SV_2_11 extends ScalaVersion { override def asString = "2.11"; def asClassifier = s"_$asString" }
  case object SV_2_12 extends ScalaVersion { override def asString = "2.12"; def asClassifier = s"_$asString" }
  case object SV_None extends ScalaVersion { override def asString = "none"; def asClassifier = ""            }

  val values: List[ScalaVersion] =
    List(SV_None, SV_2_11, SV_2_12)

  implicit val ordering = new Ordering[ScalaVersion] {
    def compare(x: ScalaVersion, y: ScalaVersion): Int =
      values.indexOf(x).compare(values.indexOf(y))
  }

  def parse(s: String): Option[ScalaVersion] =
    values.find(_.asString == s)

  val format: Format[ScalaVersion] =
    new Format[ScalaVersion] {
      override def reads(json: JsValue) =
        json.validate[String]
          .flatMap { s =>
              parse(s) match {
                case Some(env) => JsSuccess(env)
                case None      => JsError(__, s"Unsupported Scala Version '$s'")
              }
            }

      override def writes(e: ScalaVersion) =
        JsString(e.asString)
    }
}