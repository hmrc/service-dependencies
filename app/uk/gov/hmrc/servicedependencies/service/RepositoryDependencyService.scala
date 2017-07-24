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

import uk.gov.hmrc.servicedependencies.model.{LibraryVersion, MongoLibraryVersion, RepositoryDependencies, RepositoryLibraryDependencies}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

trait RepositoryDependencyService {
  def getDependencyVersionsForRepository(repositoryName: String): Future[Option[RepositoryDependencies]]
}

class DefaultRepositoryDependencyService(repositoryDependencyDataGetter: String => Future[Option[RepositoryLibraryDependencies]],
                                         libraryDependencyDataGetter: () => Future[List[MongoLibraryVersion]]) extends RepositoryDependencyService {


  override def getDependencyVersionsForRepository(repositoryName: String): Future[Option[RepositoryDependencies]] =
    for {
      dependencies <- repositoryDependencyDataGetter(repositoryName)
      references <- libraryDependencyDataGetter()
    } yield
       dependencies.map(dep => RepositoryDependencies(repositoryName, dep.libraryDependencies, references.map(LibraryVersion(_))))


}