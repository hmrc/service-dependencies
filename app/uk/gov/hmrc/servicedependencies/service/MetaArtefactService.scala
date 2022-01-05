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

package uk.gov.hmrc.servicedependencies.service

import uk.gov.hmrc.servicedependencies.model.MetaArtefact
import uk.gov.hmrc.servicedependencies.persistence.MetaArtefactRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetaArtefactService @Inject()(
  metaArtefactRepository: MetaArtefactRepository
)(implicit
  ec: ExecutionContext
) {

/**
  * Placeholder for now. Going forward we might want to think about splitting the
  * metadata up by module, parsing its dep graphs into derived collections etc
  * and doing something a bit more sophisticated than just dumping it into a collection.
  */
  def addMetaArtefact(metaArtefact: MetaArtefact): Future[Unit] =
    metaArtefactRepository.add(metaArtefact)
}
