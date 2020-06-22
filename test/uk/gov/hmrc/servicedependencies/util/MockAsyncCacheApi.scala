/*
 * Copyright 2020 HM Revenue & Customs
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

import java.util.concurrent.ConcurrentHashMap

import akka.Done
import play.api.cache.AsyncCacheApi

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class MockAsyncCacheApi(implicit val ec: ExecutionContext) extends AsyncCacheApi {

  val cache = new ConcurrentHashMap[String, Any]().asScala

  override def set(key: String, value: Any, expiration: Duration): Future[Done] = Future.successful(cache.put(key, value)).map(_ => Done)

  override def remove(key: String): Future[Done] = Future.successful(cache.remove(key)).map(_ => Done)

  override def getOrElseUpdate[A](key: String, ignored: Duration)(orElse: => Future[A])(implicit evidence$1: ClassTag[A]): Future[A] =
    cache.get(key) match {
      case Some(v) => Future.successful(v.asInstanceOf[A])
      case None    => orElse
    }

  override def get[T](key: String)(implicit ct: ClassTag[T]): Future[Option[T]] = Future.successful(cache.get(key).map(_.asInstanceOf[T]))

  override def removeAll(): Future[Done] = Future.successful(cache.clear()).map(_=> Done)
}
