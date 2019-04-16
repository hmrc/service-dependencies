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

package uk.gov.hmrc.servicedependencies.connector

import javax.inject.Inject
import play.api.cache.AsyncCacheApi
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.servicedependencies.connector.model.{BobbyRule, DeprecatedDependencies}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class ServiceConfigsConnector @Inject()(httpClient: HttpClient, servicesConfig: ServicesConfig, cache: AsyncCacheApi) {

  import ExecutionContext.Implicits.global
  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val serviceUrl: String = servicesConfig.baseUrl("service-configs")
  private val cacheExpiration: Duration =
    servicesConfig
      .getDuration("microservice.services.service-configs.cache.expiration")

  def getBobbyRules(): Future[Map[String, List[BobbyRule]]] =
    cache.getOrElseUpdate("bobby-rules", cacheExpiration) {
      httpClient
        .GET[DeprecatedDependencies](s"$serviceUrl/bobby/rules")
        .map(dependency => (dependency.libraries ++ dependency.plugins).groupBy(_.name))
    }
}