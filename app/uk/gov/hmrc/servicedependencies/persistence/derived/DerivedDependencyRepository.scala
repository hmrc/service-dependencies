/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.persistence.derived

import com.google.inject.Inject
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, ReplaceOneModel, ReplaceOptions}
import org.mongodb.scala.model.Filters.{equal, nin, or}
import org.mongodb.scala.model.Indexes.compoundIndex
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicedependencies.model.RepoType.Service
import uk.gov.hmrc.servicedependencies.model.{ApiServiceDependencyFormats, DependencyScope, MetaArtefactDependency, RepoType, SlugInfoFlag}
import uk.gov.hmrc.servicedependencies.persistence.DeploymentRepository

import scala.concurrent.{ExecutionContext, Future}

class DerivedDependencyRepository @Inject()(
                                      mongoComponent: MongoComponent,
                                      deploymentRepository: DeploymentRepository
                                    )(implicit ec: ExecutionContext
                                    ) extends PlayMongoRepository[MetaArtefactDependency](
  collectionName = "DERIVED-dependencies"
  , mongoComponent = mongoComponent
  , domainFormat   = MetaArtefactDependency.mongoFormat
  , indexes        = Seq(
    IndexModel(Indexes.ascending("slugName"), IndexOptions().name("slugNameIdx")),
    IndexModel(Indexes.ascending("group"),    IndexOptions().name("groupIdx")),
    IndexModel(Indexes.ascending("artefact"), IndexOptions().name("artefactIdx")),
    IndexModel(Indexes.ascending("repoType"), IndexOptions().name("repoIdx")),
  ) ++ DependencyScope.values.map(s => IndexModel(Indexes.ascending(s.asString + "Flag"), IndexOptions().name(s.asString + "FlagIdx")))
) with Logging {

  // automatically refreshed when given new meta data artefacts from update scheduler
  override lazy val requiresTtlIndex = false

  def addAndReplace(dependencies: Seq[MetaArtefactDependency]): Future[Unit] = {
    collection
        .bulkWrite(
          dependencies.map(d =>
            ReplaceOneModel(
              filter = Filters.and(
                equal("slugName",     d.slugName),
                equal("group",        d.group),
                equal("artefact",     d.artefact)
              ),
              replacement = d,
              replaceOptions = ReplaceOptions().upsert(true)
            )
          )
        ).toFuture()
        .map(_ => ())
  }

  def find(
            slugName: Option[String]              = None,
            repoType: Option[RepoType]            = None,
            group: Option[String]                 = None,
            artefact: Option[String]              = None,
            scopes: Option[List[DependencyScope]] = None
          ): Future[Seq[MetaArtefactDependency]] = {

    val nameFilter: Option[Bson]      = slugName.map(slugName => equal("slugName", slugName))
    val groupFilter: Option[Bson]     = group.map(group => equal("group", group))
    val artifactFilter: Option[Bson]  = artefact.map(artifact => equal("artefact", artifact))
    val scopeFilter: Bson             = scopes.fold[Bson](BsonDocument())(ss => or(ss.map(scope => equal(s"${scope.asString}Flag", value = true)): _*))
    val repoFilter: Option[Bson]      = repoType.map(repoType => equal("repoType", repoType.toString))

    val filters = Seq(nameFilter, groupFilter, artifactFilter, repoFilter, Some(scopeFilter)).flatten

    collection
      .find(if (filters.isEmpty) BsonDocument() else Filters.and(filters: _*))
      .toFuture()
  }

  def findByOtherRepository(
                             group: Option[String]                 = None,
                             artefact: Option[String]              = None,
                             scopes: Option[List[DependencyScope]] = None
                           ): Future[Seq[MetaArtefactDependency]] = {

    val groupFilter: Option[Bson]     = group.map(group => equal("group", group))
    val artifactFilter: Option[Bson]  = artefact.map(artifact => equal("artefact", artifact))
    val scopeFilter: Bson             = scopes.fold[Bson](BsonDocument())(ss => or(ss.map(scope => equal(s"${scope.asString}Flag", value = true)): _*))
    val repoFilter: Bson              = nin("repoType", Service.asString)

    val filters = Seq(groupFilter, artifactFilter, Some(repoFilter), Some(scopeFilter)).flatten

    collection
      .find(if (filters.isEmpty) BsonDocument() else Filters.and(filters: _*))
      .toFuture()
  }

  def findServicesByDeployment(
                                flag: SlugInfoFlag,
                                group: Option[String]                 = None,
                                artefact: Option[String]              = None,
                                scopes: Option[List[DependencyScope]] = None
                              ): Future[Seq[MetaArtefactDependency]]  = {

    val groupFilter: Option[Bson]     = group.map(group => equal("group", group))
    val artifactFilter: Option[Bson]  = artefact.map(artifact => equal("artefact", artifact))
    val scopeFilter: Bson             = scopes.fold[Bson](BsonDocument())(ss => or(ss.map(scope => equal(s"${scope.asString}Flag", value = true)): _*))

    val filters = Seq(groupFilter, artifactFilter, Some(scopeFilter)).flatten

    deploymentRepository.lookupAgainstDeployments(
      collectionName = collectionName,
      domainFormat = MetaArtefactDependency.mongoFormat,
      slugNameField = "slugName",
      slugVersionField = "slugVersion"
    )(
      deploymentsFilter = equal(flag.asString, true),
      domainFilter = if (filters.isEmpty) BsonDocument() else Filters.and(filters: _*)
    )
  }
}
