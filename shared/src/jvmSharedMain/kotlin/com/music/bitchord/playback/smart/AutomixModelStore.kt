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

import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
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

/** Where the Automix model download stands, for a settings row to show. */
sealed interface AutomixModelState {
    /** Not downloaded yet, or nothing has asked for them. */
    data object Idle : AutomixModelState

    /** A download is running; [fraction] is across both files. */
    data class Downloading(val fraction: Float) : AutomixModelState

    /** Both models are on disk and verified. */
    data object Ready : AutomixModelState

    /** The last attempt failed. Asking again (e.g. turning Automix off and on) retries. */
    data object Failed : AutomixModelState
}

/**
 * The two Automix models, fetched on demand rather than shipped in an installer.
 *
 * Each file comes from [RELEASE_BASE] into a `.part` file, is checked against its
 * SHA-256 and size, and only then renamed into place, so the analyser never sees a
 * half-written model. [pathOf] answers only for a complete file.
 *
 * The models live in their own release, not in an app release: the app's updater
 * reads "latest", and this is not an app version.
 *
 * Nothing here is fatal. While a model is missing, [pathOf] throws, the trackers
 * turn that into "no grid" or "no mask", and transitions fall back to a plain fade.
 *
 * Each platform owns one instance and calls [attach] with its own directory before
 * anything can ask for a model.
 */
class AutomixModelStore(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {

    private class Model(val asset: String, val sha256: String, val bytes: Long)

    private val _state = MutableStateFlow<AutomixModelState>(AutomixModelState.Idle)

    /** Where the download stands; see [AutomixModelState]. */
    val state: StateFlow<AutomixModelState> = _state

    /** Set by [attach]; null until then, which makes every entry point a no-op. */
    @Volatile private var directory: File? = null

    private val inFlight = AtomicBoolean(false)

    /**
     * Sets where the models live and records what is already there. Called once
     * per platform, before [ensureDownloaded] or [pathOf].
     */
    fun attach(dir: File) {
        dir.mkdirs()
        directory = dir
        _state.value = if (MODELS.all(::isPresent)) AutomixModelState.Ready else AutomixModelState.Idle
    }

    /**
     * The on-disk path of [asset] once it is downloaded.
     *
     * @throws IOException when the model is not present (or nothing is attached).
     */
    fun pathOf(asset: String): String {
        val model = MODELS.firstOrNull { it.asset == asset }
            ?: throw IOException("Unknown Automix model $asset")
        if (!isPresent(model)) throw IOException("Automix model $asset is not downloaded")
        return File(requireNotNull(directory), model.asset).absolutePath
    }

    /**
     * Starts the download if any model is missing. Does nothing when both are
     * present, when nothing is attached, or when a download is already running.
     */
    fun ensureDownloaded() {
        if (directory == null) return
        if (MODELS.all(::isPresent)) {
            _state.value = AutomixModelState.Ready
            return
        }
        if (!inFlight.compareAndSet(false, true)) return
        _state.value = AutomixModelState.Downloading(0f)
        scope.launch {
            try {
                runDownloads()
                _state.value = AutomixModelState.Ready
            } catch (error: CancellationException) {
                // The scope is going away: not a download failure, so no warning, but the
                // state must not stay on Downloading forever.
                _state.value = AutomixModelState.Failed
                throw error
            } catch (error: Throwable) {
                AnalysisLog.warn("Automix model download failed", error)
                _state.value = AutomixModelState.Failed
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * Downloads whatever is missing on the calling thread. For tests and tools
     * that cannot wait for the background scope. Returns whether both are present.
     */
    fun downloadBlocking(): Boolean {
        if (directory == null) return false
        if (!inFlight.compareAndSet(false, true)) return MODELS.all(::isPresent)
        return try {
            runDownloads()
            _state.value = AutomixModelState.Ready
            true
        } catch (error: Throwable) {
            AnalysisLog.warn("Automix model download failed", error)
            _state.value = AutomixModelState.Failed
            false
        } finally {
            inFlight.set(false)
        }
    }

    private fun runDownloads() {
        val total = MODELS.sumOf { it.bytes }.toFloat()
        var finished = 0L
        for (model in MODELS) {
            if (!isPresent(model)) {
                val base = finished
                download(model) { written ->
                    _state.value = AutomixModelState.Downloading((base + written) / total)
                }
            }
            finished += model.bytes
        }
    }

    /** Present and the right length. Hashing 30 MB on every open would cost more than it saves. */
    private fun isPresent(model: Model): Boolean {
        val file = directory?.let { File(it, model.asset) } ?: return false
        return file.isFile && file.length() == model.bytes
    }

    private fun download(model: Model, onProgress: (Long) -> Unit) {
        val dir = requireNotNull(directory)
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
        if (!part.renameTo(File(dir, model.asset))) {
            part.delete()
            throw IOException("Could not move ${model.asset} into place")
        }
    }

    companion object {
        /**
         * The release that carries the model files. Its own tag, marked as a
         * pre-release so GitHub's "latest" stays on the app's versions.
         */
        const val MODEL_RELEASE_TAG = "automix-models-1"
        const val RELEASE_BASE = "https://github.com/AnGorRoc/BitChord-X/releases/download/$MODEL_RELEASE_TAG"

        /** Beat This! (MIT) and open-unmix's vocal model. */
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
    }
}
