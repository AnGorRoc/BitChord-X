package com.music.bitchord

import com.music.bitchord.data.telemetry.TelemetryReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TelemetryScrubTest {
    @Test
    fun theHomeFolderAndUserNameAreRemoved() {
        val out = TelemetryReporter.scrub(
            "at C:\\Users\\maria\\app\\x.kt and C:/Users/maria/y",
            home = "C:\\Users\\maria",
            user = "maria",
        )
        assertFalse(out.contains("maria"))
        assertEquals("at ~\\app\\x.kt and ~/y", out)
    }

    @Test
    fun aUrlKeepsItsHostAndPathButNotItsQuery() {
        val out = TelemetryReporter.scrub("GET https://a.example/p/q?token=abc&x=1 failed", home = "", user = "")
        assertEquals("GET https://a.example/p/q?<query> failed", out)
    }
}
