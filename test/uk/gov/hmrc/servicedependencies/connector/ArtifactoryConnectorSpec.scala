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


  "convertToWebstoreURL" should "convert an artifactory link to a s3 webstore link" in {
    val url = "https://artefacts.test.test.test.uk/artifactory/webstore/slugs/pensions-lifetime-allowance-frontend/pensions-lifetime-allowance-frontend_2.62.0-5-g11e827d_0.5.2.tgz"
    val res = ArtifactoryConnector.convertToWebStoreURL(url)
    res shouldBe "https://webstore.test.test.test.uk/slugs/pensions-lifetime-allowance-frontend/pensions-lifetime-allowance-frontend_2.62.0-5-g11e827d_0.5.2.tgz"
  }


  "parseVersion" should "extract a version number from a uri" in {
    ArtifactoryConnector.parseVersion( "/vat-sign-up-frontend_1.55.0_0.5.2.tgz") shouldBe Some("1.55.0")
    ArtifactoryConnector.parseVersion( "/vat-sign-up-frontend_0.50.0-5-g44758bd_0.5.2.tgz") shouldBe Some("0.50.0-5-g44758bd")
    ArtifactoryConnector.parseVersion( "/rate-scheduling_189_0.5.2.tgz") shouldBe Some("189")
    ArtifactoryConnector.parseVersion( "/lost-credentials_0.15.0_0.5.2.tgz") shouldBe Some("0.15.0")

  }

  it should "return none when the version number is unparsable" in {
    val uri = "/vat-sign-up-frontend.tgz"
    ArtifactoryConnector.parseVersion(uri) shouldBe None
  }

}
