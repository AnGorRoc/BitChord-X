package com.music.bitchord.data.telemetry

import java.util.UUID

/**
 * How much the app tells the BitChord-X server about itself.
 *
 * - [FULL]: the stable install ID, version and platform, when the app is open, and error reports with
 *   their (scrubbed) details, device model and system version. What bug-fixing needs most.
 * - [MINIMAL]: that the app is open, with its version and platform, under an ID that changes every UTC
 *   day so the server cannot follow one install from one day to the next; errors are reported by type
 *   alone, with no message, stack trace or device details.
 * - [OFF]: nothing at all.
 *
 * The server enforces the same limits (it drops everything a minimal report should not carry), so
 * the guarantee does not rest on the client alone.
 */
enum class TelemetryLevel(val wire: String) {
    FULL("full"),
    MINIMAL("minimal"),
    OFF("off"),
    ;

    /** Whether anything at all leaves the device. */
    val sends: Boolean get() = this != OFF

    /** True when moving from this level to [other] means sharing less. */
    fun isMoreThan(other: TelemetryLevel): Boolean = other.ordinal > ordinal

    companion object {
        fun fromWire(value: String?): TelemetryLevel =
            values().firstOrNull { it.wire == value } ?: FULL

        /** The ID to send: the install's own at [FULL], one that changes every day at [MINIMAL]. */
        fun idFor(level: TelemetryLevel, installId: String, epochDay: Long): String = when (level) {
            MINIMAL -> UUID.nameUUIDFromBytes("bitchord-x-daily:$installId:$epochDay".toByteArray(Charsets.UTF_8)).toString()
            else -> installId
        }

        fun today(): Long = System.currentTimeMillis() / 86_400_000L
    }
}
