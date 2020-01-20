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

package uk.gov.hmrc.servicedependencies.persistence

object SlugBlacklist {
  // Initially populated with list of services without owners
  // Consider moving list to Mongo to enable updates without rebuilding/deploying
  val blacklistedSlugs = List(
        "admin-message-router"
      , "agent-fi-agent-frontend"
      , "agents-enrolment-store-stubs"
      , "api-sandbox-holding-page-frontend"
      , "auth-record-updater"
      , "aws-sns-stub"
      , "bc-passengers"
      , "bc-passengers-stub"
      , "cb-frontend"
      , "cds-proxy"
      , "cds-tariff"
      , "contact-finder"
      , "contact-voa-frontend"
      , "counter"
      , "credit-card-commission"
      , "customs-push-notification"
      , "digital-tariffs-s3"
      , "digital-tariffs-s3-admin"
      , "eacd-master-switch"
      , "emac-amazon-s3-proxy"
      , "emac-migration-frontend"
      , "emac-migration-journal"
      , "emac-migration-scheduler"
      , "enrolment-exception-list"
      , "enrolments-migrator"
      , "file-keys-manager"
      , "fuaas-api-mock-up-frontend"
      , "gap-login-mock"
      , "hello-flask-open"
      , "http-outbound-gateway"
      , "inbound-admin-message-adapter"
      , "individual-api"
      , "migration-violation-logger"
      , "mongo-auth-test"
      , "outbound-admin-message-adapter"
      , "platform-support"
      , "push-notification"
      , "push-notification-scheduler"
      , "push-registration"
      , "rate-api-proxy"
      , "rehoming-scheduler-monolith"
      , "rehoming-usher-monolith"
      , "rehoming-usher-monolith-frontend"
      , "sample-reactivemongo"
      , "service-statuses-api"
      , "sns-client"
      , "soft-drinks-industry-levy-liability-tool-frontend"
      , "user-delegation"
      , "user-delegation-frontend"
      , "verifiers-migrator"
      , "your-tax-calculator-frontend"
      )

    val blacklistedSlugsSet = blacklistedSlugs.toSet
}