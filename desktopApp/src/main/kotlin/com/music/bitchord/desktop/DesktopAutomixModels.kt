package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.AutomixModelStore
import java.io.File

/**
 * The desktop side of the Automix models: where they live on this machine. The
 * download is [AutomixModelStore], the same one the phone uses, so both fetch the
 * same verified files from the same release.
 */
internal object DesktopAutomixModels {

    /** The one store the desktop analyser and settings use. */
    private val store = AutomixModelStore().also {
        it.attach(File(DesktopAnalysisRuntime.analysisHome(), "automix-models"))
    }

    /** Where the download stands, for the settings row. */
    val state get() = store.state

    /** The on-disk path of [asset] once downloaded; throws while it is not. */
    fun pathOf(asset: String): String = store.pathOf(asset)

    /** Starts the download if any model is missing. */
    fun ensureDownloaded() = store.ensureDownloaded()

    /** Downloads on the calling thread; for tests. Returns whether the models are present. */
    fun downloadBlocking(): Boolean = store.downloadBlocking()
}
