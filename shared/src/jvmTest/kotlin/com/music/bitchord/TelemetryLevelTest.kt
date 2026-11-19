package com.music.bitchord

import com.music.bitchord.data.telemetry.TelemetryLevel
import com.music.bitchord.data.telemetry.TelemetryReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TelemetryLevelTest {
    private val install = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun fullKeepsTheInstallIdAndMinimalRotatesIt() {
        assertEquals(install, TelemetryLevel.idFor(TelemetryLevel.FULL, install, 20_000))
        val today = TelemetryLevel.idFor(TelemetryLevel.MINIMAL, install, 20_000)
        assertEquals(today, TelemetryLevel.idFor(TelemetryLevel.MINIMAL, install, 20_000))
        assertNotEquals(today, TelemetryLevel.idFor(TelemetryLevel.MINIMAL, install, 20_001))
        assertNotEquals(install, today)
    }

    @Test
    fun anotherInstallGetsAnotherDailyId() {
        val other = "223e4567-e89b-12d3-a456-426614174000"
        assertNotEquals(
            TelemetryLevel.idFor(TelemetryLevel.MINIMAL, install, 5),
            TelemetryLevel.idFor(TelemetryLevel.MINIMAL, other, 5),
        )
    }

    @Test
    fun offSendsNothingAndAnUnknownWordMeansFull() {
        assertFalse(TelemetryLevel.OFF.sends)
        assertTrue(TelemetryLevel.MINIMAL.sends)
        assertEquals(TelemetryLevel.FULL, TelemetryLevel.fromWire("nonsense"))
        assertEquals(TelemetryLevel.MINIMAL, TelemetryLevel.fromWire("minimal"))
    }

    @Test
    fun movingDownIsSharingLess() {
        assertTrue(TelemetryLevel.FULL.isMoreThan(TelemetryLevel.MINIMAL))
        assertTrue(TelemetryLevel.MINIMAL.isMoreThan(TelemetryLevel.OFF))
        assertFalse(TelemetryLevel.OFF.isMoreThan(TelemetryLevel.FULL))
    }

    @Test
    fun aMinimalReportCarriesTheTypeAndNothingElse() {
        val (message, stack) = TelemetryReporter.shaped(TelemetryLevel.MINIMAL, "uncaught_crash", "boom at /home/x", "trace")
        assertEquals("uncaught_crash", message)
        assertEquals("", stack)
        val (m2, s2) = TelemetryReporter.shaped(TelemetryLevel.FULL, "uncaught_crash", "boom", "trace")
        assertEquals("boom", m2)
        assertEquals("trace", s2)
    }
}
