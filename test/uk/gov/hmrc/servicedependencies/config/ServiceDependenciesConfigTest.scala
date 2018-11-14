package uk.gov.hmrc.servicedependencies.config

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSpec, Matchers, OptionValues}
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

class ServiceDependenciesConfigTest extends FunSpec with Matchers with MockitoSugar with OptionValues {


  describe("ServiceDependenciesConfig") {

    it("should load github credentials from config, when available") {

      val config = Configuration(
        "github.open.api.host" -> "https://api.test.url",
        "github.open.api.user" -> "testuser",
        "github.open.api.key"  -> "key123")
      val serviceConfig = mock[ServicesConfig]
      when(serviceConfig.baseUrl(any()))

      val sdc = new ServiceDependenciesConfig(config, serviceConfig)

      sdc.githubApiOpenConfig.key shouldBe "key123"
      sdc.githubApiOpenConfig.user shouldBe "testuser"
      sdc.githubApiOpenConfig.apiUrl shouldBe "https://api.test.url"
    }

  }

}
