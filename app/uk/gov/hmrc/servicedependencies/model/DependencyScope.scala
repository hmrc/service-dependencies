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

sealed trait DependencyScope { def asString: String }

object DependencyScope {
  case object Compile  extends DependencyScope { override val asString = "compile"  }
  case object Provided extends DependencyScope { override val asString = "provided" }
  case object Test     extends DependencyScope { override val asString = "test"     }
  case object It       extends DependencyScope { override val asString = "it"       }
  case object Build    extends DependencyScope { override val asString = "build"    }

  val values: List[DependencyScope] =
    List(Compile, Provided, Test, It, Build)

  def parse(s: String): Either[String, DependencyScope] =
    values
      .find(_.asString == s)
      .toRight(s"Invalid dependency scope - should be one of: ${values.map(_.asString).mkString(", ")}")

  val dependencyScopeFormat: Format[DependencyScope] = Format(
       _.validate[String].flatMap(DependencyScope.parse(_).fold(err => JsError(__, err), ds => JsSuccess(ds)))
    ,  f => JsString(f.asString)
  )


  implicit def bobbyVersionRangeStringBindable(implicit stringBinder: QueryStringBindable[String]): QueryStringBindable[DependencyScope] =
    new QueryStringBindable[DependencyScope] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, DependencyScope]] = {

        (
          for {
            x <- EitherT.apply(stringBinder.bind(key, params))
            y <- EitherT.fromEither[Option](DependencyScope.parse(x))
          } yield y
          ).value
      }

      override def unbind(key: String, value: DependencyScope): String = {
        stringBinder.unbind(key, value.toString)
      }
    }

}
