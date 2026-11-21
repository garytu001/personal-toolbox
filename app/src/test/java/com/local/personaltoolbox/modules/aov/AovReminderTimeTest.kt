package com.local.personaltoolbox.modules.aov

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AovReminderTimeTest {
    @Test fun beforeNoonSchedulesTodayInTaiwanEvenIfHostIsElsewhere() {
        assertEquals(Instant.parse("2026-11-21T04:00:00Z"), AovReminderTime.nextNoon(Instant.parse("2026-11-21T03:59:59Z")))
    }
    @Test fun atAndAfterNoonSchedulesNextDayWithoutImmediateRepeat() {
        val expected = Instant.parse("2026-11-22T04:00:00Z")
        assertEquals(expected, AovReminderTime.nextNoon(Instant.parse("2026-11-21T04:00:00Z")))
        assertEquals(expected, AovReminderTime.nextNoon(Instant.parse("2026-11-21T15:59:59Z")))
    }
    @Test fun handlesYearBoundaryAndTaiwanMidnight() {
        assertEquals(Instant.parse("2027-01-01T04:00:00Z"), AovReminderTime.nextNoon(Instant.parse("2026-12-31T16:00:00Z")))
        assertTrue(AovReminderTime.isCurrentDay("2027-01-01", Instant.parse("2026-12-31T16:00:00Z")))
        assertFalse(AovReminderTime.isCurrentDay("2026-12-31", Instant.parse("2026-12-31T16:00:00Z")))
    }
}
