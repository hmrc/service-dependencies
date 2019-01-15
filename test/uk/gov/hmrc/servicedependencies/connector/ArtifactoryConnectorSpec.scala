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
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import uk.gov.hmrc.servicedependencies.model.MongoSlugParserJob

class ArtifactoryConnectorSpec extends FlatSpec
  with Matchers
  with MockitoSugar {


  "convertToWebstoreURL" should "convert an artifactory link to a webstore link" in {
    val url = "https://artefacts.test.test.test.uk/artifactory/webstore/slugs/pensions-frontend/pensions-frontend_2.62.0-5-g11e827d_0.5.2.tgz"
    val res = ArtifactoryConnector.convertToWebStoreURL(url)
    res shouldBe "https://webstore.test.test.test.uk/slugs/pensions-frontend/pensions-frontend_2.62.0-5-g11e827d_0.5.2.tgz"
  }


  "convertToSlugParserJob" should "convert a uri to a MongoSlugParserJob" in {

    val service = "/pensions-frontend"
    val slug = "/pensions-frontend_2.62.0-5-g11e827d_0.5.2.tgz"
    val webroot = "https://webstore.test.uk/slugs"

    val result = ArtifactoryConnector.convertToSlugParserJob(service, slug, webroot).get

    result.id shouldBe None
    result.processed shouldBe false
    result.runnerVersion shouldBe "0.5.2"
    result.version shouldBe "2.62.0-5-g11e827d"
    result.service shouldBe "pensions-frontend"
    result.slugName shouldBe "/pensions-frontend_2.62.0-5-g11e827d_0.5.2.tgz"
    result.slugUri shouldBe "https://webstore.test.uk/slugs/pensions-frontend/pensions-frontend_2.62.0-5-g11e827d_0.5.2.tgz"
  }


}
