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
import android.util.Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two Automix models, downloaded the first time Automix is turned on rather
 * than shipped inside the APK. They were about half of it.
 *
 * Each file is fetched from a GitHub release into a `.part` file, checked
 * against its SHA-256 and size, and only then renamed into place. The analyser
 * therefore never sees a half-written model: [pathOf] answers only for a file
 * that is complete and the right length.
 *
 * Nothing here is fatal. While a model is missing, [TrackAnalyzer] gets an
 * exception from [pathOf], which the trackers already turn into "no grid" or
 * "no mask", and transitions fall back to a plain fade.
 */
object AutomixModels {

    /** Where things stand, for the settings row to show. */
    sealed interface State {
        /** Automix has never needed the models, or they are not on disk yet. */
        data object Idle : State

        /** A download is running; [fraction] is across both files. */
        data class Downloading(val fraction: Float) : State

        /** Both models are on disk and verified. */
        data object Ready : State

        /** The last attempt failed. Turning Automix off and on tries again. */
        data object Failed : State
    }

    private class Model(val asset: String, val sha256: String, val bytes: Long)

    /**
     * The release that carries the model files. Keep it in step with the
     * upload: `gh release create <tag> automix-models`.
     */
    private const val MODEL_RELEASE_TAG = "automix-models-1"
    private const val RELEASE_BASE = "https://github.com/AnGorRoc/BitChord-X/releases/download/$MODEL_RELEASE_TAG"

    private const val TAG = "BitChordAutomixModels"
    private const val DIRECTORY = "automix-models"

    /** Beat This! (MIT) and open-unmix's vocal model, as `automix-models/` in the repo holds them. */
    private val MODELS = listOf(
        Model(
            asset = "beat_this_final0_q8.onnx",
            sha256 = "7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd",
            bytes = 23_138_816L,
        ),
        Model(
            asset = "vocals_umxhq_int8.onnx",
            sha256 = "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb",
            bytes = 9_054_206L,
        ),
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Set by [init]; null until then, which makes every entry point a no-op. */
    @Volatile private var directory: File? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    /**
     * Records where the models live and what is already on disk. Called once
     * from the application, before anything can ask for a model.
     */
    fun init(context: Context) {
        val dir = File(context.filesDir, DIRECTORY)
        directory = dir
        // Copies unpacked from the APK's assets by earlier builds sit loose in
        // filesDir under the same names. Nothing reads them now, and they are
        // tens of megabytes.
        MODELS.forEach { File(context.filesDir, it.asset).delete() }
        _state.value = if (MODELS.all(::isPresent)) State.Ready else State.Idle
    }

    /**
     * The on-disk path of [asset], once it has been downloaded.
     *
     * @throws IOException when the model is not present yet. Callers run this
     * inside a `runCatching`, so the error reads as "no model" rather than a
     * crash.
     */
    fun pathOf(asset: String): String {
        val model = MODELS.firstOrNull { it.asset == asset }
            ?: throw IOException("Unknown Automix model $asset")
        val file = fileOf(model)
        if (!isPresent(model)) throw IOException("Automix model $asset is not downloaded")
        return file.absolutePath
    }

    /**
     * Starts the download if any model is missing. Does nothing when both are
     * already present, or when a download is already running.
     */
    fun ensureDownloaded() {
        if (directory == null) return
        if (MODELS.all(::isPresent)) {
            _state.value = State.Ready
            return
        }
        if (!inFlight.compareAndSet(false, true)) return
        _state.value = State.Downloading(0f)
        scope.launch {
            try {
                val total = MODELS.sumOf { it.bytes }.toFloat()
                var finished = 0L
                for (model in MODELS) {
                    if (!isPresent(model)) {
                        val base = finished
                        download(model) { written ->
                            _state.value = State.Downloading((base + written) / total)
                        }
                    }
                    finished += model.bytes
                }
                _state.value = State.Ready
                Log.d(TAG, "Automix models ready")
            } catch (error: Throwable) {
                Log.w(TAG, "Automix model download failed", error)
                _state.value = State.Failed
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun fileOf(model: Model): File = File(requireNotNull(directory), model.asset)

    /** Present and the right length. Hashing 30 MB on every open would cost more than it saves. */
    private fun isPresent(model: Model): Boolean {
        val file = directory?.let { File(it, model.asset) } ?: return false
        return file.isFile && file.length() == model.bytes
    }

    private fun download(model: Model, onProgress: (Long) -> Unit) {
        val dir = requireNotNull(directory)
        dir.mkdirs()
        val part = File(dir, "${model.asset}.part")
        val request = Request.Builder().url("$RELEASE_BASE/${model.asset}").build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${model.asset}")
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            response.body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != model.bytes || hash != model.sha256) {
                part.delete()
                throw IOException("Checksum mismatch for ${model.asset}: $written bytes, $hash")
            }
        }
        if (!part.renameTo(fileOf(model))) {
            part.delete()
            throw IOException("Could not move ${model.asset} into place")
        }
    }
}
