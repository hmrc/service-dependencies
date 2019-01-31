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

package uk.gov.hmrc.servicedependencies.util

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class FutureHelpers @Inject()(metrics: Metrics) {

  lazy val defaultMetricsRegistry: MetricRegistry = metrics.defaultRegistry

  def withTimerAndCounter[T](name: String)(f: Future[T]) = {
    val t = defaultMetricsRegistry.timer(s"$name.timer").time()
    f.andThen {
      case Success(_) =>
        t.stop()
        defaultMetricsRegistry.counter(s"$name.success").inc()
      case Failure(_) =>
        t.stop()
        defaultMetricsRegistry.counter(s"$name.failure").inc()
    }
  }

  def runFuturesSequentially[A, B](l: Iterable[A])(fn: A => Future[B])(implicit ec: ExecutionContext): Future[Seq[B]] =
    l.foldLeft(Future.successful(List.empty[B])) { (previousFuture, next) ⇒
      for {
        previousResults ← previousFuture
        next ← fn(next)
      } yield previousResults :+ next
    }
}

object FutureHelpers {
  implicit class FutureExtender[A](f: Future[A]) {
    def andAlso(fn: A => Unit): Future[A] =
      f.flatMap { r =>
        fn(r)
        f
      }
  }

  implicit class FutureOfBoolean(f: Future[Boolean]) {
    def &&(f1: => Future[Boolean]): Future[Boolean] = f.flatMap { bv =>
      if (!bv) Future.successful(false)
      else f1
    }
  }

  object FutureIterable {
    def apply[A](listFuture: Iterable[Future[A]]) = Future.sequence(listFuture)
  }

  implicit class FutureIterable[A](futureList: Future[Iterable[A]]) {
    def flatMap[B](fn: A => Future[Iterable[B]])(implicit ec: ExecutionContext) =
      futureList
        .flatMap { list =>
          val listOfFutures = list.map { li =>
            fn(li)
          }

          Future.sequence(listOfFutures)
        }
        .map(_.flatten)

    def map[B](fn: A => B)(implicit ec: ExecutionContext): Future[Iterable[B]] =
      futureList.map(_.map {
        fn
      })

    def filter[B](fn: A => Boolean)(implicit ec: ExecutionContext): Future[Iterable[A]] =
      futureList.map(_.filter(fn))
  }

  def continueOnError[A](f: Future[A]) =
    f.map(Success(_)).recover { case x => Failure(x) }

}
