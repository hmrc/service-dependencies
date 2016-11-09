/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies

import org.scalatest.{FreeSpec, MustMatchers}

class VersionSpec
  extends FreeSpec
  with MustMatchers {

  "Can be lower than others" in {
    Version(0, 0, 0) < Version(0, 0, 1) mustBe true
    Version(0, 0, 1) < Version(0, 0, 0) mustBe false

    Version(0, 0, 0) < Version(0, 1, 0) mustBe true
    Version(0, 1, 0) < Version(0, 0, 0) mustBe false

    Version(0, 0, 0) < Version(1, 0, 0) mustBe true
    Version(1, 0, 0) < Version(0, 0, 0) mustBe false

    Version(0, 1, 1) < Version(1, 0, 0) mustBe true
    Version(1, 0, 0) < Version(0, 1, 1) mustBe false

    Version(1, 0, 1) < Version(1, 1, 0) mustBe true
    Version(1, 1, 0) < Version(1, 0, 1) mustBe false

    Version(1, 1, 0) < Version(1, 1, 1) mustBe true
    Version(1, 1, 1) < Version(1, 1, 0) mustBe false
  }

  "Can be equal" in {
    Version(1, 2, 3) mustBe Version(1, 2, 3)
  }

  "Can be parsed from strings" in {
    Version.parse("1.2.3") mustBe Version(1, 2, 3)
  }

  "Can be printed to strings" in {
    Version(1,2,3).toString mustBe "1.2.3"
  }
}
