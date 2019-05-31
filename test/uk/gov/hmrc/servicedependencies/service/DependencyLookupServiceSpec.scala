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

package uk.gov.hmrc.servicedependencies.service
import java.time.LocalDate

import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, FunSpec, Matchers}
import uk.gov.hmrc.servicedependencies.connector.model.{BobbyRule, BobbyVersion, BobbyVersionRange}
import uk.gov.hmrc.servicedependencies.model._
import org.mockito.Mockito._
import uk.gov.hmrc.servicedependencies.connector.ServiceConfigsConnector
import uk.gov.hmrc.servicedependencies.persistence.SlugInfoRepository

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class DependencyLookupServiceTest extends FlatSpec with Matchers with MockitoSugar{

  import DependencyLookupServiceTestData._
  import scala.concurrent.ExecutionContext.Implicits.global

  "slugToServiceDep" should "Convert a slug and dependency to a ServiceDependency" in {


    val res = DependencyLookupService.slugToServiceDep(slug1, dep1)

    res.depSemanticVersion shouldBe Some(Version(5,11,0))
    res.slugName           shouldBe "test"
    res.slugVersion        shouldBe "1.0.0"
  }


  "BuildLookup" should "Build a map of dependencies showing which slug uses which version" in {
    val res = DependencyLookupService.buildLookup(Seq(slug11, slug1, slug12))

    res("org.libs:mylib").keySet.contains(Version(5, 11, 0))
    res("org.libs:mylib").keySet.contains(Version(5, 12, 0))
    res("org.libs:mylib")(Version(5, 11, 0)).size shouldBe 2
    res("org.libs:mylib")(Version(5, 11, 0)).exists(_.slugVersion == "1.0.0") shouldBe true
    res("org.libs:mylib")(Version(5, 11, 0)).exists(_.slugVersion == "1.1.0") shouldBe true
    res("org.libs:mylib")(Version(5, 12, 0)).size shouldBe 1
    res("org.libs:mylib")(Version(5, 12, 0)).exists(_.slugVersion == "1.2.0") shouldBe true
  }


  "findSlugsUsing" should "return a list of slugs inside the version range" in {

    val slugLookup: Map[String, Map[Version, Set[ServiceDependency]]] = Map(
      "org.libs:mylib" -> Map(
         Version(1,0,0) -> Set(ServiceDependency("test1", "1.99.3",List.empty, "org.libs","mylib","1.0.0"))
        ,Version(1,3,0) -> Set(ServiceDependency("test2", "2.0.1",List.empty, "org.libs","mylib","1.3.0"))))

    val res1 = DependencyLookupService.findSlugsUsing(
      lookup   = slugLookup,
      group    = "org.libs",
      artifact = "mylib",
      range    = BobbyVersionRange.parse("[1.1.0,]").get)

    res1.length shouldBe 1

    val res2 = DependencyLookupService.findSlugsUsing(
      lookup   = slugLookup,
      group    = "org.libs",
      artifact = "mylib",
      range    = BobbyVersionRange.parse("(1.0.0,]").get)

    res2.length shouldBe 1
  }


  "countBobbyRuleViolations" should "return the number of slugs violating a bobby rule" in {
    val configService      = mock[ServiceConfigsConnector]
    val slugInfoRepository = mock[SlugInfoRepository]

    when(configService.getBobbyRules()).thenReturn(Future(Map("libs" -> List(bobbyRule))))
    when(slugInfoRepository.getSlugsForEnv(SlugInfoFlag.Production)).thenReturn(Future(Seq(slug1, slug11, slug12)))

    val lookupService = new DependencyLookupService(configService, slugInfoRepository)
    val count         = Await.result(lookupService.countBobbyRuleViolations(SlugInfoFlag.Production), Duration(20, "seconds"))

    count.get(bobbyRule) shouldBe Some(1)
  }


}


object DependencyLookupServiceTestData {

  val dep1: SlugDependency = SlugDependency("", "5.11.0", "org.libs", "mylib")
  val dep2: SlugDependency = SlugDependency("", "5.12.0", "org.libs", "mylib")

  val slug1 = SlugInfo(uri = "http://slugs.com/test/test-1.0.0.tgz"
    ,name          = "test"
    ,version       = Version("1.0.0")
    ,teams         = List.empty
    ,runnerVersion = "0.5.2"
    ,classpath     = "classpath="
    ,jdkVersion    = "1.8.0"
    ,dependencies  = List(dep1)
    ,applicationConfig = "config"
    ,""
    ,latest       = true
    ,production   = false
    ,qa           = false
    ,staging      = false
    ,development  = false
    ,externalTest = false)

  val slug11 = slug1.copy(version = Version(1,1,0), uri = "http://slugs.com/test/test-1.1.0.tgz")

  val slug12 = slug1.copy(version = Version(1,2,0), uri = "http://slugs.com/test/test-1.2.0.tgz", dependencies = List(dep2))

  val bobbyRule = BobbyRule(organisation = dep1.group, name = dep1.artifact, range = BobbyVersionRange.parse("(5.11.0,]").get, "testing", LocalDate.of(2000,1,1))
}