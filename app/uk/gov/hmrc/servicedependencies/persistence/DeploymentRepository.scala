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
import org.mongodb.scala.{ClientSession, ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.bson.{BsonArray, BsonDocument}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{Aggregates, Filters, Indexes, IndexModel, Updates, UpdateOptions, Sorts, Variable}
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
)(using ec: ExecutionContext
) extends PlayMongoRepository[Deployment](
  collectionName = "deployments",
  mongoComponent = mongoComponent,
  domainFormat   = Deployment.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("name", "version")),
                     IndexModel(Indexes.ascending("name")),
                   ) ++ SlugInfoFlag.values.map(f => IndexModel(Indexes.hashed(f.asString))),
  replaceIndexes = true
) with Transactions:
  val logger = Logger(getClass)

  private implicit val tc: TransactionConfiguration =
    TransactionConfiguration.strict

  def clearFlag(flag: SlugInfoFlag, name: String): Future[Unit] =
    withSessionAndTransaction: session =>
      clearFlag(flag, name, session)

  private def clearFlag(flag: SlugInfoFlag, name: String, session: ClientSession): Future[Unit] =
    logger.debug(s"clear ${flag.asString} flag on $name")
    collection
      .updateMany(
          clientSession = session,
          filter        = Filters.and(
                            Filters.equal("name", name),
                            Filters.equal(flag.asString, true)
                          ),
          update        = Updates.set(flag.asString, false)
        )
      .toFuture()
      .map(_ => ())

  def clearFlags(flags: List[SlugInfoFlag], names: List[String]): Future[Unit] =
    logger.debug(s"clearing ${flags.size} flags on ${names.size} services")
    collection
      .updateMany(
          filter = Filters.in("name", names*),
          update = Updates.combine(flags.map(flag => Updates.set(flag.asString, false))*)
        )
      .toFuture()
      .map(_ => ())

  def find(flag: SlugInfoFlag, name: String): Future[Option[Deployment]] =
    collection
      .find(Filters.and(Filters.equal(flag.asString, true), Filters.equal("name", name)))
      .headOption()

  def findDeployed(name: Option[String] = None): Future[Seq[Deployment]] =
    collection
      .find:
        Filters.and(
          name.fold(Filters.empty())(n => Filters.equal("name", n))
        , Filters.or(SlugInfoFlag.values.map(v => Filters.equal(v.asString, true))*)
        )
      .sort(Sorts.ascending("name"))
      .toFuture()

  def getNames(flag: SlugInfoFlag): Future[Seq[String]] =
    collection
      .find(Filters.equal(flag.asString, true))
      .map(_.slugName)
      .toFuture()

  def markLatest(name: String, version: Version): Future[Unit] =
    setFlag(SlugInfoFlag.Latest, name, version)

  // TODO more efficient way to sync this data with release-api?
  def setFlag(flag: SlugInfoFlag, name: String, version: Version): Future[Unit] =
    withSessionAndTransaction: session =>
      for
        _ <- clearFlag(flag, name, session)
        _ =  logger.debug(s"mark slug $name $version with ${flag.asString} flag")
        _ <- collection
               .updateOne(
                 clientSession = session,
                 filter        = Filters.and(
                                   Filters.equal("name", name),
                                   Filters.equal("version", version.original),
                                 ),
                 update        = Updates.set(flag.asString, true),
                 options       = UpdateOptions().upsert(true)
               )
               .toFuture()
      yield ()

  def lookupAgainstDeployments[A: ClassTag](
    collectionName  : String,
    domainFormat    : Format[A],
    slugNameField   : String,
    slugVersionField: String
  )(
    deploymentsFilter: Bson,
    domainFilter     : Bson,
    pipeline         : Seq[Bson] = List.empty
  ): Future[Seq[A]] =
    CollectionFactory.collection(mongoComponent.database, "deployments", domainFormat)
      .aggregate(
        List(
          Aggregates.`match`(deploymentsFilter),
          Aggregates.lookup(
            from     = collectionName,
            let      = Seq(
                         Variable("sn", "$name"),
                         Variable("sv", "$version")
                       ),
            pipeline = List(
                         Aggregates.`match`(
                           Filters.and(
                             Filters.expr(
                               Filters.and(
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
          Aggregates.unwind("$res"),
          Aggregates.replaceRoot("$res")
        ) ++ pipeline
      ).toFuture()

  def delete(repositoryName: String, version: Version): Future[Unit] =
    collection
      .deleteOne(
          Filters.and(
            Filters.equal("name"   , repositoryName),
            Filters.equal("version", version.toString)
          )
        )
      .toFuture()
      .map(_ => ())


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
):
  lazy val flags: List[SlugInfoFlag] =
    SlugInfoFlag
      .values.toList
      .filter:
        case SlugInfoFlag.Latest       => latest
        case SlugInfoFlag.Production   => production
        case SlugInfoFlag.QA           => qa
        case SlugInfoFlag.Staging      => staging
        case SlugInfoFlag.Development  => development
        case SlugInfoFlag.ExternalTest => externalTest
        case SlugInfoFlag.Integration  => integration

object Deployment:
  val mongoFormat: Format[Deployment] =
    given Format[Version] = Version.format
    ( (__ \ "name"        ).format[String]
    ~ (__ \ "version"     ).format[Version]
    ~ (__ \ "latest"      ).formatWithDefault[Boolean](false)
    ~ (__ \ "production"  ).formatWithDefault[Boolean](false)
    ~ (__ \ "qa"          ).formatWithDefault[Boolean](false)
    ~ (__ \ "staging"     ).formatWithDefault[Boolean](false)
    ~ (__ \ "development" ).formatWithDefault[Boolean](false)
    ~ (__ \ "externaltest").formatWithDefault[Boolean](false)
    ~ (__ \ "integration" ).formatWithDefault[Boolean](false)
    )(Deployment.apply, d => Tuple.fromProductTyped(d))
