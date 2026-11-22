/*
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.smart

import android.content.Context
import java.io.File

/**
 * The Android side of the Automix models: where they live on the phone. The
 * download itself is [AutomixModelStore], shared with the desktop.
 */
object AutomixModels {

    private const val DIRECTORY = "automix-models"

    /** The one store the phone's analyser and settings use. */
    private val store = AutomixModelStore()

    /** Where the download stands, for the settings row. */
    val state get() = store.state

    /**
     * Points the store at the app's own storage. Called once from the application,
     * before anything can ask for a model.
     */
    fun init(context: Context) {
        // Copies unpacked from the APK's assets by earlier builds sit loose in
        // filesDir under the same names. Nothing reads them now, and they are
        // tens of megabytes.
        listOf("beat_this_final0_q8.onnx", "vocals_umxhq_int8.onnx")
            .forEach { File(context.filesDir, it).delete() }
        store.attach(File(context.filesDir, DIRECTORY))
    }

    /** The on-disk path of [asset] once downloaded; throws while it is not. See [AutomixModelStore.pathOf]. */
    fun pathOf(asset: String): String = store.pathOf(asset)

    /** Starts the download if any model is missing. See [AutomixModelStore.ensureDownloaded]. */
    fun ensureDownloaded() = store.ensureDownloaded()
}
