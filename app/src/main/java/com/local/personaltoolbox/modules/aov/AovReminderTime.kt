package com.local.personaltoolbox.modules.aov

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar-based scheduling avoids drifting after reboots or clock changes. */
internal object AovReminderTime {
    val zone: ZoneId = ZoneId.of("Asia/Taipei")
    fun today(now: Instant = Instant.now()): LocalDate = now.atZone(zone).toLocalDate()
    fun nextNoon(now: Instant): Instant {
        val noon = today(now).atTime(12, 0).atZone(zone).toInstant()
        return if (noon > now) noon else today(now).plusDays(1).atTime(12, 0).atZone(zone).toInstant()
    }
    fun isCurrentDay(date: String?, now: Instant = Instant.now()): Boolean = date == today(now).toString()
}
