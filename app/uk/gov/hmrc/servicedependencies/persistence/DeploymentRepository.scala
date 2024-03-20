/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.servicedependencies.persistence

import play.api.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.ClientSession
import org.mongodb.scala.bson.{BsonArray, BsonDocument}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{IndexModel, IndexOptions, UpdateOptions, Variable}
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates.{set, _}
import org.mongodb.scala.model.Indexes.{ascending, compoundIndex}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{CollectionFactory, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.servicedependencies.model.{SlugInfoFlag, Version}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

// TODO would a model of name, version, flag=compile/test/build - be better?
@Singleton
class DeploymentRepository @Inject()(
  final val mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository[Deployment](
  collectionName = "deployments",
  mongoComponent = mongoComponent,
  domainFormat   = Deployment.mongoFormat,
  indexes        = Seq(
                     IndexModel(
                       compoundIndex(ascending("name"), ascending("version")),
                       IndexOptions().name("nameVersionIdx")
                     ),
                     IndexModel(
                       compoundIndex(SlugInfoFlag.values.map(f => ascending(f.asString)) :_*),
                       IndexOptions().name("slugInfoFlagIdx").background(true)
                     )
                   )
) with Transactions {
  val logger = Logger(getClass)

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def clearFlag(flag: SlugInfoFlag, name: String): Future[Unit] =
    withSessionAndTransaction { session =>
      clearFlag(flag, name, session)
   }

  private def clearFlag(flag: SlugInfoFlag, name: String, session: ClientSession): Future[Unit] = {
    logger.debug(s"clear ${flag.asString} flag on $name")
    collection
      .updateMany(
          clientSession = session,
          filter        = equal("name", name),
          update        = set(flag.asString, false)
        )
      .toFuture()
      .map(_ => ())
  }

  def clearFlags(flags: List[SlugInfoFlag], names: List[String]): Future[Unit] = {
    logger.debug(s"clearing ${flags.size} flags on ${names.size} services")
    collection
      .updateMany(
          filter = in("name", names:_ *),
          update = combine(flags.map(flag => set(flag.asString, false)):_ *)
        )
      .toFuture()
      .map(_ => ())
  }

  def find(): Future[Seq[Deployment]] =
    collection
      .find() // TODO can every flag be false?
      .toFuture()

  def getNames(flag: SlugInfoFlag): Future[Seq[String]] =
    collection
      .find(equal(flag.asString, true))
      .map(_.slugName)
      .toFuture()

  def markLatest(name: String, version: Version): Future[Unit] =
    setFlag(SlugInfoFlag.Latest, name, version)

  // TODO more efficient way to sync this data with release-api?
  def setFlag(flag: SlugInfoFlag, name: String, version: Version): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- clearFlag(flag, name, session)
        _ =  logger.debug(s"mark slug $name $version with ${flag.asString} flag")
        _ <- collection
               .updateOne(
                 clientSession = session,
                 filter        = and(
                                   equal("name", name),
                                   equal("version", version.original),
                                 ),
                 update        = set(flag.asString, true),
                 options       = UpdateOptions().upsert(true)
               )
               .toFuture()
      } yield ()
    }

  def lookupAgainstDeployments[A: ClassTag](
    collectionName: String,
    domainFormat: Format[A],
    slugNameField: String,
    slugVersionField: String
  )(
    deploymentsFilter: Bson,
    domainFilter     : Bson,
    pipeline         : Seq[Bson] = List.empty
  ): Future[Seq[A]] =
    CollectionFactory.collection(mongoComponent.database, "deployments", domainFormat)
      .aggregate(
        List(
          `match`(
            and(
              deploymentsFilter,
              nin("name", SlugDenylist.denylistedSlugs)
            )
          ),
          lookup(
            from     = collectionName,
            let      = Seq(
                         Variable("sn", "$name"),
                         Variable("sv", "$version")
                       ),
            pipeline = List(
                         `match`(
                           and(
                             expr(
                               and(
                                 // can't use Filters.eq which strips the $eq out, and thus complains about $name/$version not being operators
                                 BsonDocument("$eq" -> BsonArray("$" + slugNameField, "$$sn")),
                                 BsonDocument("$eq" -> BsonArray("$" + slugVersionField, "$$sv"))
                               )
                             ),
                             domainFilter
                           )
                         )
                       ),
            as       = "res"
          ),
          unwind("$res"),
          replaceRoot("$res")
        ) ++ pipeline
      ).toFuture()

  def delete(repositoryName: String, version: Version): Future[Unit] =
    collection
      .deleteOne(
          and(
            equal("name"   , repositoryName),
            equal("version", version.toString)
          )
        )
      .toFuture()
      .map(_ => ())

  def clearAllData(): Future[Unit] =
    collection.deleteMany(BsonDocument())
      .toFuture()
      .map(_ => ())
}


case class Deployment(
  slugName     : String,
  slugVersion  : Version,
  latest       : Boolean,
  production   : Boolean,
  qa           : Boolean,
  staging      : Boolean,
  development  : Boolean,
  externalTest : Boolean,
  integration  : Boolean
) {
  lazy val flags: List[SlugInfoFlag] =
    SlugInfoFlag
      .values
      .filter {
        case SlugInfoFlag.Latest       => latest
        case SlugInfoFlag.Production   => production
        case SlugInfoFlag.QA           => qa
        case SlugInfoFlag.Staging      => staging
        case SlugInfoFlag.Development  => development
        case SlugInfoFlag.ExternalTest => externalTest
        case SlugInfoFlag.Integration  => integration
      }
}

object Deployment {
  val mongoFormat: Format[Deployment] = {
    implicit val vf = Version.format
    ( (__ \ "name"          ).format[String]
    ~ (__ \ "version"       ).format[Version]
    ~ (__ \ "latest"        ).formatWithDefault[Boolean](false)
    ~ (__ \ "production"    ).formatWithDefault[Boolean](false)
    ~ (__ \ "qa"            ).formatWithDefault[Boolean](false)
    ~ (__ \ "staging"       ).formatWithDefault[Boolean](false)
    ~ (__ \ "development"   ).formatWithDefault[Boolean](false)
    ~ (__ \ "external test" ).formatWithDefault[Boolean](false)
    ~ (__ \ "integration"   ).formatWithDefault[Boolean](false)
    )(Deployment.apply, unlift(Deployment.unapply))
  }
}
