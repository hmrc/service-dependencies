/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.config

import com.google.inject.{Inject, Singleton}
import play.api.Configuration

import scala.concurrent.duration.FiniteDuration

@Singleton
class ArtefactReceivingConfig @Inject()(configuration: Configuration) {

  private lazy val sqsQueueUrlPrefix   : String = configuration.get[String]("artefact.receiver.aws.sqs.queue-prefix")
  private lazy val sqsQueueSlugInfo    : String = configuration.get[String]("artefact.receiver.aws.sqs.queue-slug")
  private lazy val sqsQueueMetaArtefact: String = configuration.get[String]("artefact.receiver.aws.sqs.queue-meta")

  lazy val sqsMetaArtefactQueue           = s"$sqsQueueUrlPrefix/$sqsQueueMetaArtefact"
  lazy val sqsMetaArtefactDeadLetterQueue = s"$sqsQueueUrlPrefix/$sqsQueueMetaArtefact-deadletter"

  lazy val sqsSlugQueue                   = s"$sqsQueueUrlPrefix/$sqsQueueSlugInfo"
  lazy val sqsSlugDeadLetterQueue         = s"$sqsQueueUrlPrefix/$sqsQueueSlugInfo-deadletter"

  /** includes whether the payloads are compressed */
  lazy val sqsDeadLetterQueues: Map[String, Boolean] =
    Map(
      sqsSlugDeadLetterQueue         -> true,
      sqsMetaArtefactDeadLetterQueue -> false
    )

  lazy val isEnabled: Boolean =
    configuration.get[Boolean]("artefact.receiver.enabled")

  lazy val metaArtefactRetryDelay: FiniteDuration =
    configuration.get[FiniteDuration]("artefact.receiver.meta-artefact.retry.delay")
}
