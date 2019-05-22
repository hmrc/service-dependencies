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

package uk.gov.hmrc.servicedependencies.controller.model

import uk.gov.hmrc.servicedependencies.model.SlugDependency

object SlugFactory {

  val aSlug =
    Slug(
      uri             = "https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz",
      name            = "my-slug",
      version         = "0.27.0",
      teams           = List("Team"),
      runnerVersion   = "0.5.2",
      classpath       = "classpath",
      jdkVersion      = "1.181.0",
      dependencies    = List(
        SlugDependency(
          path     = "lib1",
          version  = "1.2.0",
          group    = "com.test.group",
          artifact = "lib1"
        ),
        SlugDependency(
          path     = "lib2",
          version  = "0.66",
          group    = "com.test.group",
          artifact = "lib2")),
      applicationConfig = "applicationConfig",
      slugConfig        = "slugConfig",
      latest            = true)

}
