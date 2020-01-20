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

package uk.gov.hmrc.servicedependencies.persistence

import java.time.LocalDateTime

import uk.gov.hmrc.servicedependencies.model._

object TestSlugInfos {
  val slugInfo =
    SlugInfo(
      created       = LocalDateTime.of(2019, 6, 28, 11, 51, 23),
      uri           = "https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz",
      name          = "my-slug",
      version       = Version.apply("0.27.0"),
      teams         = List.empty,
      runnerVersion = "0.5.2",
      classpath     = "",
      java          = JavaInfo("1.181.0", "OpenJDK", "JRE"),
      dependencies = List(
        SlugDependency(
          path     = "lib1",
          version  = "1.2.0",
          group    = "com.test.group",
          artifact = "lib1"
        ),
        SlugDependency(path = "lib2", version = "0.66", group = "com.test.group", artifact = "lib2")
      ),
      applicationConfig = "",
      slugConfig        = "",
      latest            = true,
      production        = true,
      qa                = true,
      staging           = true,
      development       = true,
      externalTest      = true,
      integration       = true
    )

  val oldSlugInfo = slugInfo.copy(
    uri     = "https://store/slugs/my-slug/my-slug_0.26.0_0.5.2.tgz",
    version = Version.apply("0.26.0"),
    latest  = false
  )

  val otherSlug =
    SlugInfo(
      created       = LocalDateTime.of(2019, 6, 28, 11, 51, 23),
      uri           = "https://store/slugs/other-slug/other-slug_0.55.0_0.5.2.tgz",
      name          = "other-slug",
      version       = Version.apply("0.55.0"),
      teams         = List.empty,
      runnerVersion = "0.5.2",
      classpath     = "",
      java          = JavaInfo("1.191.0", "Oracle", "JDK"),
      dependencies = List(
        SlugDependency(
          path     = "lib3",
          version  = "1.66.1",
          group    = "io.stuff",
          artifact = "lib3"
        )),
      applicationConfig = "",
      slugConfig        = "",
      latest            = true,
      production        = true,
      qa                = true,
      staging           = true,
      development       = true,
      externalTest      = true,
      integration       = true
    )

  val nonJavaSlugInfo = slugInfo.copy(
    uri  = "https://store/slugs/nodejs-app/nodejs-app_0.1.0_0.5.2.tgz",
    name = "nodejs-app",
    java = JavaInfo("", "", "")
  )
}
