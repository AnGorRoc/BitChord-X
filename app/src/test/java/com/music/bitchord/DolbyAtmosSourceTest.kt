package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether the Dolby Atmos setting has anything to ask — see [SourceRegistry.mayServeDolbyAtmos]. */
class DolbyAtmosSourceTest {

    @Test
    fun `youtube and the stereo catalogues alone cannot serve atmos`() {
        val configs = listOf(
            SourceConfig(kind = SourceKind.YOUTUBE),
            SourceConfig(kind = SourceKind.JIOSAAVN, enabled = true),
            SourceConfig(kind = SourceKind.TIDAL, enabled = true),
            SourceConfig(kind = SourceKind.MONOCHROME, enabled = true),
        )
        assertFalse(SourceRegistry.mayServeDolbyAtmos(configs))
    }

    @Test
    fun `an enabled addon with an address can`() {
        val configs = listOf(
            SourceConfig(kind = SourceKind.YOUTUBE),
            SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://addon.example", enabled = true),
        )
        assertTrue(SourceRegistry.mayServeDolbyAtmos(configs))
    }

    @Test
    fun `a switched off or unfinished addon does not count`() {
        val configs = listOf(
            SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://addon.example", enabled = false),
            SourceConfig(kind = SourceKind.ADDON, baseUrl = "", enabled = true),
        )
        assertFalse(SourceRegistry.mayServeDolbyAtmos(configs))
    }
}
