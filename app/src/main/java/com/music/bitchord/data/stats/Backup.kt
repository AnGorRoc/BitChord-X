package com.music.bitchord.data.stats

import android.content.Context
import android.net.Uri
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

private val compactJson = Json(Backup.json) { prettyPrint = false }

fun initAndroidBackup() {
    Backup.platformExporter = { context, target ->
        withContext(Dispatchers.IO) {
            runCatching {
                val c = context as Context
                val u = target as Uri
                c.contentResolver.openOutputStream(u, "wt")
                    ?.use { Backup.writeTo(it, pretty = true) }
                    ?: error("Couldn't open that file for writing")
            }
        }
    }
    Backup.platformImporter = { context, source ->
        withContext(Dispatchers.IO) {
            runCatching {
                val c = context as Context
                val u = source as Uri
                c.contentResolver.openInputStream(u)
                    ?.use { Backup.readFrom(it) }
                    ?: error("Couldn't open that file")
            }
        }
    }
}

suspend fun Backup.exportTo(context: Context, target: Uri): Result<Int> =
    exportTo(context as Any, target as Any)

suspend fun Backup.importFrom(context: Context, source: Uri): Result<Backup.Summary> =
    importFrom(context as Any, source as Any)

/**
 * Writes a backup into [out], returning how many months of listening it
 * held. [out] is left open: the caller owns it.
 *
 * Streamed rather than built as one String and then copied to bytes, which
 * held the whole file in memory twice over — fine at a few hundred KB, an
 * out-of-memory waiting to happen in a background worker once Replay has
 * years in it. [pretty] is for a file someone may open and read; the Drive
 * copy is compressed and never read by a person, so it goes without.
 */
@OptIn(ExperimentalSerializationApi::class)
suspend fun Backup.writeTo(out: OutputStream, pretty: Boolean): Int = withContext(Dispatchers.IO) {
    val buckets = ListeningStats.exportAll()
    val file = Backup.BackupFile(
        versionName = BuildConfig.VERSION_NAME,
        exportedAt = Instant.now().toString(),
        settings = AppSettings.exportPrefs().mapNotNull { (key, value) ->
            Backup.PrefValue.of(value)?.let { key to it }
        }.toMap(),
        listening = buckets,
    )
    (if (pretty) Backup.json else compactJson).encodeToStream(Backup.BackupFile.serializer(), file, out)
    out.flush()
    buckets.size
}

/**
 * Reads a backup from [input] and applies it, under the same all-or-nothing
 * rule as [importFrom]: everything is parsed and checked before anything on
 * this device is touched. [input] is left open: the caller owns it.
 */
@OptIn(ExperimentalSerializationApi::class)
suspend fun Backup.readFrom(input: InputStream): Backup.Summary = withContext(Dispatchers.IO) {
    val file = runCatching { Backup.json.decodeFromStream(Backup.BackupFile.serializer(), input) }
        .getOrElse { error("That doesn't look like a BitChord backup") }
    require(file.app == Backup.APP_TAG) { "That backup is from another app" }
    require(file.version <= Backup.SCHEMA_VERSION) {
        "That backup was written by a newer version of BitChord"
    }

    ListeningStats.importAll(file.listening)
    AppSettings.importPrefs(file.settings.mapValues { it.value.decoded() })
    // Shares AppSettings' preference file, so it has already been
    // overwritten by the line above — it just doesn't know yet.
    SearchHistory.reload()
    Backup.Summary(
        months = file.listening.size,
        settings = file.settings.size,
        from = file.versionName,
        at = file.exportedAt,
    )
}
