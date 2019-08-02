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

package uk.gov.hmrc.servicedependencies.model

import java.time.LocalDateTime

import org.scalatest.FlatSpec
import play.api.libs.json.Json

class ApiSlugInfoFormatsSpec extends FlatSpec {

  "api json format" should "handle missing javainfo sections " in {

    import ApiSlugInfoFormats._

    println(Json.toJson(LocalDateTime.now()))
    val res = Json.parse(jsonWithJavaInfo).validate[SlugInfo]

    println(res)
  }

  val jsonWithJavaInfo =
    """{
      |    "uri" : "https://artifactory/slugs/mobile-auth-stub/mobile-service_0.12.0_0.5.2.tgz",
      |    "name" : "mobile-service",
      |    "version" : "0.12.0",
      |    "created" : "2019-07-26T13:41:45.052",
      |    "runnerVersion" : "0.5.2",
      |    "classpath" : "$lib_dir/../conf/:$lib_dir/uk.gov.hmrc.mobile-auth-stub-0.12.0-sans-externalized.jar:$lib_dir/org.scala-lang.scala-library-2.11.12.jar",
      |    "jdkVersion" : "1.8.0_191",
      |    "dependencies" : [
      |        {
      |            "path" : "./mobile-auth-stub-0.12.0/lib/org.slf4j.slf4j-api-1.7.25.jar",
      |            "version" : "1.7.25",
      |            "group" : "org.slf4j",
      |            "artifact" : "slf4j-api",
      |            "meta" : "fromPom"
      |        }
      |    ],
      |    "slugConfig": "",
      |    "applicationConfig": "",
      |    "teams": [],
      |    "latest" : false,
      |    "qa" : false,
      |    "production" : false,
      |    "development" : false,
      |    "external test" : false,
      |    "staging" : false,
      |    "java" : {
      |        "version" : "1.8.0_191",
      |        "kind" : "JDK",
      |        "vendor" : "OpenJDK"
      |    },
      |    "Extra": {}
      |}
    """.stripMargin

  val jsonWithoutJavaInfo =
    """{
      |    "uri" : "https://artifactory/slugs/mobile-auth-stub/mobile-service_0.12.0_0.5.2.tgz",
      |    "name" : "mobile-service",
      |    "version" : "0.12.0",
      |    "created" : "2019-07-26T13:41:45.052",
      |    "runnerVersion" : "0.5.2",
      |    "classpath" : "$lib_dir/../conf/:$lib_dir/uk.gov.hmrc.mobile-auth-stub-0.12.0-sans-externalized.jar:$lib_dir/org.scala-lang.scala-library-2.11.12.jar",
      |    "jdkVersion" : "1.8.0_191",
      |    "dependencies" : [
      |        {
      |            "path" : "./mobile-auth-stub-0.12.0/lib/org.slf4j.slf4j-api-1.7.25.jar",
      |            "version" : "1.7.25",
      |            "group" : "org.slf4j",
      |            "artifact" : "slf4j-api",
      |            "meta" : "fromPom"
      |        }
      |    ],
      |    "slugConfig": "",
      |    "applicationConfig": "",
      |    "teams": [],
      |    "latest" : false,
      |    "qa" : false,
      |    "production" : false,
      |    "development" : false,
      |    "external test" : false,
      |    "staging" : false
      |}
    """.stripMargin


}
