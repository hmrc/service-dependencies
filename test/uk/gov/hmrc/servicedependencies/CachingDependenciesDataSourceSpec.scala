/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Future, Promise}
import scala.util.Success

class CachingDependenciesDataSourceSpec extends WordSpec with BeforeAndAfterAll with ScalaFutures with Matchers with DefaultPatienceConfig with Eventually {

  val system = ActorSystem.create()

  override def afterAll(): Unit = {
    system.terminate()
  }

  val testConfig = new CacheConfig() {
    override def cacheDuration: FiniteDuration = FiniteDuration(100, TimeUnit.SECONDS)
  }

  def withCache(dataLoader:() => Future[Seq[ServiceDependencies]], testConfig:CacheConfig = testConfig)(block: (CachingDependenciesDataSource) => Unit): Unit ={
    val cache = new CachingDependenciesDataSource(system, testConfig, dataLoader)
    block(cache)
  }

  "Caching teams repository data source" should {

    "return an uncompleted future when called before the cache has been populated" in {
      val promise1 = Promise[Seq[ServiceDependencies]]()

      withCache(() => promise1.future){ cache =>
        cache.getCachedData.isCompleted shouldBe false
      }
    }

    "return the current result when the cache is in the process of reloading" in {
      val (promise1, promise2) = (Promise[Seq[ServiceDependencies]](), Promise[Seq[ServiceDependencies]]())
      val ResultValue = Seq[ServiceDependencies]()

      val cachedData = Iterator[Promise[Seq[ServiceDependencies]]](promise1, promise2).map(_.future)

      withCache(() => cachedData.next) { cache =>
        promise1.complete(Success(ResultValue))

        eventually {
          cache.getCachedData.futureValue shouldBe ResultValue
        }

        cache.reload()

        cache.getCachedData.isCompleted shouldBe true
        cache.getCachedData.futureValue shouldBe ResultValue
      }
    }


    "return the updated result when the cache has completed reloading" in {
      val (promise1, promise2) = (Promise[Seq[ServiceDependencies]](), Promise[Seq[ServiceDependencies]]())

      val cachedData = Iterator[Promise[Seq[ServiceDependencies]]](promise1, promise2).map(_.future)

      withCache(() => cachedData.next) { cache =>
        promise1.success(Seq[ServiceDependencies]())

        eventually {
          cache.getCachedData.isCompleted shouldBe true
        }

        cache.reload()

        promise2.success(Seq(ServiceDependencies("another", Map())))

        eventually {
          cache.getCachedData.futureValue shouldBe Seq(ServiceDependencies("another", Map()))
        }
      }
    }

    "return a completed future when the cache has been populated" in {

      val (promise1, promise2) = (Promise[Seq[ServiceDependencies]](), Promise[Seq[ServiceDependencies]]())
      val cachedData = Iterator[Promise[Seq[ServiceDependencies]]](promise1, promise2).map(_.future)

      withCache(() => cachedData.next) { cache =>

        val future1 = cache.getCachedData
        future1.isCompleted shouldBe false
        promise1.complete(Success(Seq[ServiceDependencies]()))
        eventually {
          future1.futureValue shouldBe Seq[ServiceDependencies]()
        }
      }
    }

    "populate the cache from the data source and retain it until the configured expiry time" in {

      val testConfig = new CacheConfig() {
        override def cacheDuration: FiniteDuration = 100 millis
      }

      val cachedData = Iterator(
        Seq(ServiceDependencies("result1", Map())),
        Seq(ServiceDependencies("result2", Map())),
        Seq(ServiceDependencies("result3", Map()))).map(Future.successful)

      withCache(() => cachedData.next, testConfig) { cache =>

        eventually {
          cache.getCachedData.futureValue shouldBe Seq(ServiceDependencies("result1", Map()))
        }

        eventually {
          cache.getCachedData.futureValue shouldBe Seq(ServiceDependencies("result2", Map()))
        }
      }
    }
  }
}
