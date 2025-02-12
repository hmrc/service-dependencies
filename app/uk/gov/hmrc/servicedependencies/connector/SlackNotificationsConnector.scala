/*
 * Copyright 2025 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.Configuration
import uk.gov.hmrc.servicedependencies.connector.SlackNotificationsConnector.ChannelLookup

@Singleton
class SlackNotificationsConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig,
  configuration: Configuration
)(using
  ec: ExecutionContext
) extends Logging:
  private val url      : String = servicesConfig.baseUrl("slack-notifications")
  private val authToken: String = servicesConfig.getString("internal-auth.token")
  private val channelOverride: Option[String] = configuration.getOptional[String]("slack-notifications.channelOverride")

  def sendMessage(request: SlackNotificationsConnector.Request)(using HeaderCarrier): Future[SlackNotificationsConnector.Response] =
    given Writes[SlackNotificationsConnector.Request] = SlackNotificationsConnector.Request.writes
    given Reads[SlackNotificationsConnector.Response] = SlackNotificationsConnector.Response.reads
    val message = channelOverride.fold(request)(c => request.copy(channelLookup = ChannelLookup.SlackChannels(Seq(c))))
    httpClientV2
      .post(url"$url/slack-notifications/v2/notification")
      .withBody(Json.toJson(message))
      .setHeader("Authorization" -> authToken)
      .execute[SlackNotificationsConnector.Response]
      .recoverWith:
        case NonFatal(ex) =>
          logger.error(s"Unable to notify ${message.channelLookup} on Slack", ex)
          Future.failed(ex)

object SlackNotificationsConnector:
  case class Error(
    code   : String,
    message: String
  )

  case class Response(
    errors: Seq[Error]
  )

  object Response:
    val reads: Reads[Response] =
      given Reads[Error] =
        ( (__ \ "code"   ).read[String]
        ~ (__ \ "message").read[String]
        )(Error.apply)

      (__ \ "errors")
        .readWithDefault[List[Error]](List.empty)
        .map(Response.apply)

  enum ChannelLookup(val by: String):
    case GithubTeam   (teamName      : String     ) extends ChannelLookup("github-team")
    case OwningTeams  (repositoryName: String     ) extends ChannelLookup("github-repository")
    case SlackChannels(slackChannels : Seq[String]) extends ChannelLookup("slack-channel")

  case class Request(
    channelLookup  : ChannelLookup,
    displayName    : String,
    emoji          : String,
    text           : String,
    blocks         : Seq[JsValue],
    callbackChannel: Option[String] = None
  )

  object Request:
    val writes: Writes[Request] =
      given OWrites[ChannelLookup.GithubTeam   ] = Writes.at[String     ](__ \ "teamName"      ).contramap[ChannelLookup.GithubTeam   ](_.teamName)
      given OWrites[ChannelLookup.OwningTeams  ] = Writes.at[String     ](__ \ "repositoryName").contramap[ChannelLookup.OwningTeams  ](_.repositoryName)
      given OWrites[ChannelLookup.SlackChannels] = Writes.at[Seq[String]](__ \ "slackChannels" ).contramap[ChannelLookup.SlackChannels](_.slackChannels)
      given Writes[ChannelLookup] =
        Writes {
          case s: ChannelLookup.GithubTeam    => Json.obj("by" -> s.by).deepMerge(Json.toJsObject(s))
          case s: ChannelLookup.OwningTeams   => Json.obj("by" -> s.by).deepMerge(Json.toJsObject(s))
          case s: ChannelLookup.SlackChannels => Json.obj("by" -> s.by).deepMerge(Json.toJsObject(s))
        }

      ( (__ \ "channelLookup"  ).write[ChannelLookup]
      ~ (__ \ "displayName"    ).write[String]
      ~ (__ \ "emoji"          ).write[String]
      ~ (__ \ "text"           ).write[String]
      ~ (__ \ "blocks"         ).write[Seq[JsValue]]
      ~ (__ \ "callbackChannel").writeNullable[String]
      )(o => Tuple.fromProductTyped(o))
  
  def withDivider(messages: Seq[JsValue]): Seq[JsValue] =
    Json.parse("""{"type": "divider"}"""") +: messages

  def mrkdwnBlock(message: String): JsValue =
    Json.parse(s"""{
      "type": "section",
      "text": {
        "type": "mrkdwn",
        "text": "$message"
      }
    }""")
