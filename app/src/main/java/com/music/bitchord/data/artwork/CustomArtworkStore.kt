package com.music.bitchord.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Stores and manages user-customized artwork:
 * 1. Custom album title logos (transparent PNGs).
 * 2. Custom cropped covers for releases (albums, playlists, folders) (square JPGs).
 */
object CustomArtworkStore {

    private const val TAG = "CustomArtworkStore"
    private const val DIR_ALBUM_LOGOS = "album_logos"
    private const val DIR_COVERS = "covers"
    private const val DIR_LEGACY_PLAYLIST_COVERS = "playlist_covers"

    private val _updates = MutableStateFlow(0L)
    val updates: StateFlow<Long> = _updates.asStateFlow()

    private val _coverEditRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val coverEditRequests: SharedFlow<String> = _coverEditRequests.asSharedFlow()

    fun requestCoverEdit(browseId: String) {
        _coverEditRequests.tryEmit(browseId)
    }

    private fun sanitizeKey(key: String): String =
        key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(120)

    fun albumKey(albumTitle: String, artistName: String): String =
        "${artistName.trim().lowercase()}|${albumTitle.trim().lowercase()}"

    private fun getDir(context: Context, subDir: String): File {
        val root = File(context.filesDir, "custom_artwork/$subDir")
        if (!root.exists()) root.mkdirs()
        return root
    }

    fun getLogoPath(context: Context, id: String?, albumTitle: String = "", artistName: String = ""): String? {
        if (!id.isNullOrBlank()) {
            val key = sanitizeKey(id)
            val file = File(getDir(context, DIR_ALBUM_LOGOS), "$key.png")
            if (file.exists() && file.length() > 0) return file.absolutePath
        }
        if (albumTitle.isNotBlank()) {
            val key = sanitizeKey(albumKey(albumTitle, artistName))
            val file = File(getDir(context, DIR_ALBUM_LOGOS), "$key.png")
            if (file.exists() && file.length() > 0) return file.absolutePath
        }
        return null
    }

    fun getAlbumLogoPath(context: Context, albumTitle: String, artistName: String): String? =
        getLogoPath(context, null, albumTitle, artistName)

    fun getCoverPath(context: Context, id: String): String? {
        val key = sanitizeKey(id)
        val file = File(getDir(context, DIR_COVERS), "$key.jpg")
        if (file.exists() && file.length() > 0) return file.absolutePath
        val legacy = File(getDir(context, DIR_LEGACY_PLAYLIST_COVERS), "$key.jpg")
        return if (legacy.exists() && legacy.length() > 0) legacy.absolutePath else null
    }

    fun getPlaylistCoverPath(context: Context, playlistId: String): String? =
        getCoverPath(context, playlistId)

    suspend fun saveLogo(
        context: Context,
        id: String?,
        albumTitle: String = "",
        artistName: String = "",
        sourceUri: Uri,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val key = if (!id.isNullOrBlank()) sanitizeKey(id) else sanitizeKey(albumKey(albumTitle, artistName))
            val destFile = File(getDir(context, DIR_ALBUM_LOGOS), "$key.png")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (!id.isNullOrBlank() && albumTitle.isNotBlank()) {
                val altKey = sanitizeKey(albumKey(albumTitle, artistName))
                if (altKey != key) {
                    val altFile = File(getDir(context, DIR_ALBUM_LOGOS), "$altKey.png")
                    runCatching { destFile.copyTo(altFile, overwrite = true) }
                }
            }
            _updates.value = System.currentTimeMillis()
            Log.d(TAG, "Saved logo at ${destFile.absolutePath}")
            destFile.absolutePath
        }.getOrNull()
    }

    suspend fun saveAlbumLogo(
        context: Context,
        albumTitle: String,
        artistName: String,
        sourceUri: Uri,
    ): String? = saveLogo(context, null, albumTitle, artistName, sourceUri)

    suspend fun removeLogo(
        context: Context,
        id: String?,
        albumTitle: String = "",
        artistName: String = "",
    ) = withContext(Dispatchers.IO) {
        if (!id.isNullOrBlank()) {
            val key = sanitizeKey(id)
            val file = File(getDir(context, DIR_ALBUM_LOGOS), "$key.png")
            if (file.exists()) file.delete()
        }
        if (albumTitle.isNotBlank()) {
            val key = sanitizeKey(albumKey(albumTitle, artistName))
            val file = File(getDir(context, DIR_ALBUM_LOGOS), "$key.png")
            if (file.exists()) file.delete()
        }
        _updates.value = System.currentTimeMillis()
        Log.d(TAG, "Removed logo for id=$id title=$albumTitle")
    }

    suspend fun removeAlbumLogo(
        context: Context,
        albumTitle: String,
        artistName: String,
    ) = removeLogo(context, null, albumTitle, artistName)

    suspend fun saveCover(
        context: Context,
        id: String,
        bitmap: Bitmap,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val key = sanitizeKey(id)
            val destFile = File(getDir(context, DIR_COVERS), "$key.jpg")
            FileOutputStream(destFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }
            _updates.value = System.currentTimeMillis()
            Log.d(TAG, "Saved custom cover for $id at ${destFile.absolutePath}")
            destFile.absolutePath
        }.getOrNull()
    }

    suspend fun savePlaylistCover(
        context: Context,
        playlistId: String,
        bitmap: Bitmap,
    ): String? = saveCover(context, playlistId, bitmap)

    suspend fun removeCover(
        context: Context,
        id: String,
    ) = withContext(Dispatchers.IO) {
        val key = sanitizeKey(id)
        val file = File(getDir(context, DIR_COVERS), "$key.jpg")
        if (file.exists()) file.delete()
        val legacy = File(getDir(context, DIR_LEGACY_PLAYLIST_COVERS), "$key.jpg")
        if (legacy.exists()) legacy.delete()
        _updates.value = System.currentTimeMillis()
        Log.d(TAG, "Removed custom cover for $id")
    }

    suspend fun removePlaylistCover(
        context: Context,
        playlistId: String,
    ) = removeCover(context, playlistId)
}
