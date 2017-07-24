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

package uk.gov.hmrc.servicedependencies.service

import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSpec, Matchers, OptionValues}
import org.scalatest.mock.MockitoSugar
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.Future

class RepositoryDependencyServiceSpec extends FunSpec with MockitoSugar with Matchers with ScalaFutures with OptionValues {

  private val repositoryDependencyDataGetter = mock[String => Future[Option[RepositoryLibraryDependencies]]]
  private val libraryDependencyDataGetter = mock[() => Future[List[MongoLibraryVersion]]]
  val repositoryDependencyService = new DefaultRepositoryDependencyService(repositoryDependencyDataGetter, libraryDependencyDataGetter)

  describe("getDependencyVersionsForRepository") {
    it("should use the data getters to collect the repository dependencies") {

      val repoName = "repoAbc"
      val libName = "libName"

      val version123 = Version(1, 2, 3)
      val version456 = Version(4, 5, 6)

      when(repositoryDependencyDataGetter.apply(any()))
        .thenReturn(Future.successful(Some(RepositoryLibraryDependencies(repoName, Seq(LibraryDependency(libName,version123))))))

      when(libraryDependencyDataGetter.apply())
        .thenReturn(Future.successful(List(MongoLibraryVersion(libName, version456))))

      val repositoryDependencies = repositoryDependencyService.getDependencyVersionsForRepository(repoName)

      repositoryDependencies.futureValue.value shouldBe
        RepositoryDependencies(
          repositoryName = repoName,
          libraryDependencies = List(LibraryDependency(libName, version123)),
          referenceLibraryVersions = List(LibraryVersion(libName, version456))
        )
    }
  }
}
