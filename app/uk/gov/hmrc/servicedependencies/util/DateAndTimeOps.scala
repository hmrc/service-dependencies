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

package uk.gov.hmrc.servicedependencies.util

import java.time.DayOfWeek._
import java.time.temporal.ChronoField
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}

object DateAndTimeOps:

  private val workingDays = List(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

  extension (localDate: LocalDate)
    def toInstant: Instant =
      localDate.atStartOfDay().toInstant(ZoneOffset.UTC)

  def isInWorkingHours(instant: Instant): Boolean =
    val localDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
    val hour          = localDateTime.toLocalTime.get(ChronoField.HOUR_OF_DAY)
    val dayOfWeek     = localDateTime.toLocalDate.getDayOfWeek
    workingDays.contains(dayOfWeek) && hour >= 9 && hour <= 17
