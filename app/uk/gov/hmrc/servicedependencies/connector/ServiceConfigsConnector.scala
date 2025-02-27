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

package uk.gov.hmrc.servicedependencies.connector

import javax.inject.Inject
import play.api.cache.AsyncCacheApi
import play.api.libs.json.{Reads, __}
import play.api.libs.functional.syntax.*
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicedependencies.model.{BobbyRule, BobbyRules}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class ServiceConfigsConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig,
  cache         : AsyncCacheApi
)(using
  ec: ExecutionContext
):
  import HttpReads.Implicits._

  private given HeaderCarrier = HeaderCarrier()

  private val serviceUrl: String = servicesConfig.baseUrl("service-configs")

  private val cacheExpiration: Duration =
    servicesConfig
      .getDuration("microservice.services.service-configs.cache.expiration")

  private case class DeprecatedDependencies(
    libraries: Seq[BobbyRule]
  , plugins  : Seq[BobbyRule]
  )

  private given Reads[DeprecatedDependencies] =
    ( (__ \ "libraries").lazyRead(Reads.seq[BobbyRule](BobbyRule.format))
    ~ (__ \ "plugins"  ).lazyRead(Reads.seq[BobbyRule](BobbyRule.format))
    )(DeprecatedDependencies.apply)

  def getBobbyRules(): Future[BobbyRules] =
    cache.getOrElseUpdate("bobby-rules", cacheExpiration):
      httpClientV2
        .get(url"$serviceUrl/service-configs/bobby/rules")
        .execute[DeprecatedDependencies]
        .map: dependencies =>
          BobbyRules:
            (dependencies.libraries.toList ++ dependencies.plugins)
              .groupBy(dependency => (dependency.organisation, dependency.name))
