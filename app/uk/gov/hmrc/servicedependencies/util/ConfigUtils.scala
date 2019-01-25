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

package uk.gov.hmrc.servicedependencies.util

import akka.actor.ActorSystem
import javax.inject.Inject
import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.servicedependencies.service.{SlugJobCreator, SlugJobProcessor}
import uk.gov.hmrc.servicedependencies.persistence.MongoLocks

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationLong, FiniteDuration}

trait ConfigUtils {
  def getDuration(configuration: Configuration, key: String) =
    Option(configuration.getMillis(key))
      .map(_.milliseconds)
      .getOrElse(throw new RuntimeException(s"$key not specified"))
}

object ConfigUtils extends ConfigUtils
