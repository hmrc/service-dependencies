/*
 * Copyright 2023 HM Revenue & Customs
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

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.model.Aggregates.project
import org.mongodb.scala.model.Filters.{equal, notEqual}
import org.mongodb.scala.model.Projections.{computed, fields}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SbtVersionRepository @Inject()(
  mongoComponent      : MongoComponent,
  deploymentRepository: DeploymentRepository
)(using
  ec: ExecutionContext
) extends SlugInfoRepositoryBase[SBTVersion](
  mongoComponent,
  domainFormat = SBTVersionFormats.sbtVersionFormat
):
  def findSBTUsage(flag: SlugInfoFlag): Future[Seq[SBTVersion]] =
    deploymentRepository.lookupAgainstDeployments(
      collectionName   = "slugInfos",
      domainFormat     = SBTVersionFormats.sbtVersionFormat,
      slugNameField    = "name",
      slugVersionField = "version"
    )(
      deploymentsFilter = equal(flag.asString, true),
      domainFilter      = notEqual("sbtVersion", null),
      pipeline          = Seq(
                            project(
                              fields(
                                computed("serviceName", "$name"),
                                computed("version", "$sbtVersion")
                              )
                            )
                          )
    )
