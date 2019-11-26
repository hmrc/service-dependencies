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

package uk.gov.hmrc.servicedependencies.persistence

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.model.Aggregates.{`match`, project}
import org.mongodb.scala.model.Filters.{and, equal, nin, notEqual}
import org.mongodb.scala.model.Projections.{computed, fields}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.throttle.{ThrottleConfig, WithThrottling}
import uk.gov.hmrc.servicedependencies.model._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class JdkVersionRepository @Inject()(
      mongo             : MongoComponent,
      val throttleConfig: ThrottleConfig
    )(implicit ec: ExecutionContext
    ) extends SlugInfoRepositoryBase[JDKVersion](
      mongo,
      domainFormat   = MongoSlugInfoFormats.jdkVersionFormat
    ) with WithThrottling {

  def findJDKUsage(flag: SlugInfoFlag): Future[Seq[JDKVersion]] = {
    val agg = List(
      `match`(
        and(
          equal(flag.asString, true),
          notEqual("java.version", ""),
          nin("name", SlugBlacklist.blacklistedSlugs)
        )
      ),
      project(
        fields(
          computed("name", "$name"),
          //Using the f interpolator below to prevent a false positive warning on 'missing interpolator', which is detected
          //by the 'java' keyword, which is 'plausible' enough as a real package for the compiler to give us the warning
          //See: https://github.com/scala/scala/pull/5053/files/275305a3d291cca49163903b5b6fe1d496b507a6#diff-4eab1aad4533a31c10565971e90f73eaR5209
          //And: https://stackoverflow.com/questions/39401213/disable-false-warning-possible-missing-interpolator
          computed("version", f"$$java.version"),
          computed("vendor", f"$$java.vendor"),
          computed("kind", f"$$java.kind")
        ))
    )
    collection.aggregate(agg)
      .toThrottledFuture
  }
}
