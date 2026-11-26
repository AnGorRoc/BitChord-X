package com.music.bitchord.desktop

import com.music.bitchord.data.Http
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.remote.RemoteArtReader
import com.music.bitchord.data.smb.SmbClient
import com.music.bitchord.data.smb.SmbConfig

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.decodeToImageBitmap
import okhttp3.Request
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Decoded artwork, kept to a budget.
 *
 * It used to be an unbounded map. Every distinct URL a session ever loaded stayed decoded for the
 * life of the process, so browsing Explore and Search for a while was hundreds of megabytes of
 * bitmaps nothing would ever ask for again — which is where the gigabyte people reported was going.
 */
internal object DesktopArtworkCache {

    /**
     * A sixth of the heap, within reason.
     *
     * Derived rather than fixed so the budget still makes sense against whatever `-Xmx` the
     * launcher was given, and clamped at both ends so a small heap still caches something and a
     * large one does not decide it may hold half a gigabyte of thumbnails.
     */
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)

    /** Four bytes a pixel, which is what every format here decodes to. */
    private val images = DesktopByteBudgetLru<ImageBitmap>(budgetBytes) {
        it.width.toLong() * it.height.toLong() * 4L
    }

    /** Everything held, dropped — what the Storage settings' "Clear image cache" does. */
    fun clear() {
        images.clear()
        runCatching {
            Files.list(diskDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        }
    }

    /**
     * Kept on disk beside the media cache, so a cover fetched once is still there after a restart,
     * as Android's Coil disk cache keeps it. Its own folder, so the media cache's trim never touches it.
     */
    private val diskDir: Path by lazy {
        DesktopMediaCache.directory.resolveSibling("artwork").also { runCatching { Files.createDirectories(it) } }
    }

    /** Bound on the on-disk covers, the same 150 MB Android's image disk cache allows. */
    private const val DISK_LIMIT_BYTES = 150L * 1024 * 1024

    private fun diskFileFor(url: String): Path {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return diskDir.resolve("$digest.img")
    }

    /** Reads a cover kept from an earlier run, or null when there is none or it no longer decodes. */
    private fun readDisk(file: Path): ImageBitmap? {
        if (!Files.isReadable(file) || runCatching { Files.size(file) }.getOrDefault(0L) <= 0L) return null
        return runCatching { Files.readAllBytes(file).decodeToImageBitmap() }
            .getOrNull()
            ?: run { runCatching { Files.deleteIfExists(file) }; null }
    }

    private fun writeDisk(file: Path, bytes: ByteArray) {
        runCatching {
            Files.write(file, bytes)
            trimDisk()
        }
    }

    /** Drops the least recently written covers once the folder is over its bound. */
    private fun trimDisk() {
        val files = runCatching {
            Files.list(diskDir).use { stream -> stream.filter(Files::isRegularFile).toList() }
        }.getOrDefault(emptyList())
        var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        if (total <= DISK_LIMIT_BYTES) return
        files.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            .forEach { path ->
                if (total <= DISK_LIMIT_BYTES) return
                val size = runCatching { Files.size(path) }.getOrDefault(0L)
                if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) total -= size
            }
    }

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        images.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                when {
                    url.startsWith("embedded:") -> {
                        val streamUrl = url.removePrefix("embedded:")
                        val isSmb = SmbConfig.isSmbUrl(streamUrl)
                        RemoteArtReader.picture(streamUrl, isSmb)?.bytes?.decodeToImageBitmap()
                    }
                    SmbConfig.isSmbUrl(url) -> {
                        SmbClient.readFully(url).decodeToImageBitmap()
                    }
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        val file = diskFileFor(url)
                        readDisk(file) ?: run {
                            val request = Request.Builder().url(url).build()
                            Http.client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val bytes = response.body.bytes()
                                    writeDisk(file, bytes)
                                    bytes.decodeToImageBitmap()
                                } else {
                                    null
                                }
                            }
                        }
                    }
                    else -> {
                        URI(url).toURL().openConnection().apply {
                            connectTimeout = 10_000
                            readTimeout = 10_000
                        }.getInputStream().use { stream -> stream.readBytes().decodeToImageBitmap() }
                    }
                }
            }.getOrNull()?.also { images.put(url, it) }
        }
    }
}

/** Artwork, fetched at the size the surface drawing it actually needs. */
@Composable
fun DesktopArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    contentDescription: String? = null,
    px: Int? = null,
) {
    val sized = if (px != null) url.artworkAt(px) else url
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = sized) {
        value = withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(sized)
        }
    }
    if (bitmap == null) {
        Box(modifier.background(com.music.bitchord.ui.theme.themed(Color(0xFF2B2B35), Color(0xFFE5E5EA))))
    } else {
        Image(
            painter = BitmapPainter(bitmap!!),
            contentDescription = contentDescription,
            contentScale = contentScale,
            alignment = alignment,
            modifier = modifier,
        )
    }
}
