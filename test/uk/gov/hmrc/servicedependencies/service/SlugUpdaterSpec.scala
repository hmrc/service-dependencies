package uk.gov.hmrc.servicedependencies.service
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.scalatest.{FlatSpecLike, Matchers}
import uk.gov.hmrc.servicedependencies.connector.ArtifactoryConnector
import uk.gov.hmrc.servicedependencies.connector.model.{ArtifactoryChild, ArtifactoryRepo}
import uk.gov.hmrc.servicedependencies.model.MongoSlugParserJob
import uk.gov.hmrc.servicedependencies.persistence.SlugParserJobsRepository
import org.mockito.ArgumentMatchers.{any, eq => eqTo}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits.global

class SlugUpdaterSpec extends TestKit(ActorSystem("MySpec"))
  with ImplicitSender
  with FlatSpecLike
  with MockitoSugar
  with Matchers {

  val mockRepo = mock[SlugParserJobsRepository]
  val mockConnector = mock[ArtifactoryConnector]



  "update" should "write a number of mongojobs to the database" in {

    val slug1 = ArtifactoryChild("/test-service", true)
    val slug2 = ArtifactoryChild("/abc", true)
    val allRepos = ArtifactoryRepo("webstore", "2018-04-30T09:06:22.544Z", "2018-04-30T09:06:22.544Z", Seq(slug1, slug2))

    val slugJob = MongoSlugParserJob(None, "","","","0.5.3", "http://")

    when(mockConnector.findAllSlugs()).thenReturn(Future(List(slug1, slug2)))
    when(mockConnector.findAllSlugsForService(any())).thenReturn(Future(List(slugJob, slugJob)))
    when(mockRepo.add(any())).thenReturn(Future())

    val slugUpdater = new SlugUpdater(mockConnector, mockRepo, RateLimit(10000, FiniteDuration(100, "seconds")), ActorMaterializer())

    slugUpdater.update()

    Thread.sleep(1000)
    verify(mockRepo, times(4)).add(any())

  }

}
