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

package uk.gov.hmrc.servicedependencies.util

import uk.gov.hmrc.servicedependencies.model.Version
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class MaxSpec extends AnyFunSpec with Matchers {

  describe("maxOf") {

    it("should find the maximum Version") {
      Max.maxOf(
        Seq(
          Some(Version(1, 0, 0)),
          Some(Version(1, 1, 0)),
          Some(Version(1, 1, 1)),
          Some(Version(1, 1, 10)),
          Some(Version(1, 1, 2))
        )) shouldBe Some(Version(1, 1, 10))
    }

    it("should be able to handle Nones") {
      Max.maxOf(
        Seq(
          Some(Version(1, 1, 0)),
          None,
          Some(Version(1, 1, 10)),
          Some(Version(1, 1, 2))
        )) shouldBe Some(Version(1, 1, 10))
    }

    it("should be able to handle empty Seq") {
      Max.maxOf(Seq.empty[Option[Version]]) shouldBe None
    }
  }

}
