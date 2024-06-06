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

import cats.data.EitherT
import play.api.libs.json._
import play.api.mvc.QueryStringBindable

enum DependencyScope(val asString: String):
  case Compile  extends DependencyScope("compile" )
  case Provided extends DependencyScope("provided")
  case Test     extends DependencyScope("test"    )
  case It       extends DependencyScope("it"      )
  case Build    extends DependencyScope("build"   )

object DependencyScope:
  val valuesAsSeq: Seq[DependencyScope] =
    scala.collection.immutable.ArraySeq.unsafeWrapArray(values)

  def parse(s: String): Either[String, DependencyScope] =
    values
      .find(_.asString == s)
      .toRight(s"Invalid dependency scope - should be one of: ${values.map(_.asString).mkString(", ")}")

  val dependencyScopeFormat: Format[DependencyScope] =
    Format(
      _.validate[String].flatMap(parse(_).fold(err => JsError(__, err), ds => JsSuccess(ds)))
    , f => JsString(f.asString)
    )


  given bobbyVersionRangeStringBindable(using stringBinder: QueryStringBindable[String]): QueryStringBindable[DependencyScope] with
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, DependencyScope]] =
      (for
          x <- EitherT.apply(stringBinder.bind(key, params))
          y <- EitherT.fromEither[Option](DependencyScope.parse(x))
        yield y
      ).value

    override def unbind(key: String, value: DependencyScope): String =
      stringBinder.unbind(key, value.toString)
