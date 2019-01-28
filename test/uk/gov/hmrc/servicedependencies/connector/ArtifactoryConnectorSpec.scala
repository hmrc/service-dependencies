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

    val result = ArtifactoryConnector.convertToSlugParserJob(service, slug, webroot)

    result.slugUri shouldBe "https://webstore.test.uk/slugs/pensions-frontend/pensions-frontend_2.62.0-5-g11e827d_0.5.2.tgz"
  }


  "toDownloadURL" should "Convert an artifactory API link to a downloadable url" in {

    val url = "https://artefacts.test.test.test.uk/artifactory/api/storage/webstore-local/slugs/api-platform-test-user/api-platform-test-user_0.73.0_0.5.2.tgz"

    val res = ArtifactoryConnector.toDownloadURL(url)

    res shouldBe "https://webstore.test.test.test.uk/webstore/slugs/api-platform-test-user/api-platform-test-user_0.73.0_0.5.2.tgz"
  }


}
