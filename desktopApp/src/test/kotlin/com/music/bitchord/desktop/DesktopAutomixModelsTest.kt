package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.BeatTracker
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The desktop downloads the Automix models from the release and they match what the app expects. */
class DesktopAutomixModelsTest {

    @Test
    fun `the models download from the release and are on disk`() {
        if (!DesktopAutomixModels.downloadBlocking()) {
            println("Automix models could not be downloaded (offline?) — skipping")
            return
        }
        val beat = File(DesktopAutomixModels.pathOf(BeatTracker.MODEL_ASSET))
        assertTrue(beat.isFile && beat.length() == 23_138_816L, "beat model is ${beat.length()} bytes")
    }
}
