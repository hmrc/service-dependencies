package uk.gov.hmrc.servicedependencies.connector
import javax.inject.Inject
import play.api.libs.ws.WSClient
import uk.gov.hmrc.servicedependencies.config.ServiceDependenciesConfig


class SlugConnector @Inject()(ws: WSClient, serviceConfiguration: ServiceDependenciesConfig){


  def downloadSlug(slug: String)  = {
    ???
  }
}
