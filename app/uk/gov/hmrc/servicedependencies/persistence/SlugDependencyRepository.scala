package uk.gov.hmrc.servicedependencies.persistence
import com.google.inject.{Inject, Singleton}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.servicedependencies.model.SlugDependencyInfo

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugDependencyRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[SlugDependencyInfo, BSONObjectID](
    collectionName = "slugDependencies",
    mongo          = mongo.mongoConnector.db,
    domainFormat   = SlugDependencyInfo.format){

}
