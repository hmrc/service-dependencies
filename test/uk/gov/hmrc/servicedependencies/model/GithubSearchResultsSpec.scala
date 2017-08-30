/*
 * Copyright 2017 HM Revenue & Customs
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

import org.scalatest.{FreeSpec, Matchers}
import org.scalatest.mock.MockitoSugar

class GithubSearchResultsSpec extends FreeSpec with MockitoSugar with Matchers {

  "GithubSearchResults" - {
    "isEmpty when" - {
      "sbtPlugins and libraries are empty and no 'Some' values in others" in {
        GithubSearchResults(Map.empty, Map.empty, Map("a" -> Some(Version(1,2,3)))).isEmpty shouldBe true

      }

    }
    "isEmpty is false when" - {
      "sbtPlugins or libraries is not empty" in {
        GithubSearchResults(Map.empty, Map("a" -> Some(Version(1,2,3))), Map("a" -> None)).isEmpty shouldBe false
        GithubSearchResults(Map.empty, Map("a" -> Some(Version(1,2,3))), Map.empty).isEmpty shouldBe false
        GithubSearchResults(Map("a" -> Some(Version(1,2,3))), Map.empty, Map.empty).isEmpty shouldBe false
      }

    }

  }

}
