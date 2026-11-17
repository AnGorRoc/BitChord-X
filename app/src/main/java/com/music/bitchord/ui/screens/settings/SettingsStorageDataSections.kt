package com.music.bitchord.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LosslessCacheLevel
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.data.stats.exportTo
import com.music.bitchord.data.stats.importFrom
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun SettingsLocalMusicSection(search: SettingsSearch) {
    val context = LocalContext.current
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()

    val localMusicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { folder ->
        if (folder == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        AppSettings.setLocalMusicFolderUri(folder.toString())
    }

    SearchableSettingsGroup(search, header = stringResource(R.string.local_music)) {
        val localMusicFolderTitle = stringResource(R.string.local_music_folder)
        row(localMusicFolderTitle, "folder", "offline", "files") {
            SettingsRow(
                icon = SfIcons.Folder,
                title = localMusicFolderTitle,
                subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                    ?: stringResource(R.string.all_audio_folders),
                onClick = { localMusicFolderPicker.launch(null) },
            )
        }
        if (localMusicFolderUri.isNotBlank()) {
            val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
            row(useAllAudioFoldersTitle, "folder") {
                SettingsRow(
                    icon = SfIcons.LibraryMusic,
                    title = useAllAudioFoldersTitle,
                    subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                    onClick = { AppSettings.setLocalMusicFolderUri("") },
                )
            }
        }
        val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
        row(filterNonMusicAudioTitle, "podcast", "recording") {
            SettingsRow(
                icon = SfIcons.FilterAlt,
                title = filterNonMusicAudioTitle,
                subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                trailing = {
                    IosSwitch(
                        checked = filterNonMusicAudio,
                        onCheckedChange = AppSettings::setFilterNonMusicAudio,
                    )
                },
                onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
            )
        }
    }
}

@Composable
internal fun SettingsStorageSection(
    search: SettingsSearch,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
) {
    val context = LocalContext.current
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val losslessCacheLevel by AppSettings.losslessCacheLevel.collectAsStateWithLifecycle()
    val losslessCacheLimitBytes by AppSettings.losslessCacheLimitBytes.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val improveCachedSongs by AppSettings.improveCachedSongs.collectAsStateWithLifecycle()
    val smartPreload by AppSettings.smartPreload.collectAsStateWithLifecycle()

    var confirmClearSongCache by remember { mutableStateOf(false) }

    val cacheUnlimited = cacheLimitBytes == AppSettings.UNLIMITED_CACHE_LIMIT_BYTES
    val cacheDisabled = cacheLimitBytes == AppSettings.DISABLED_CACHE_LIMIT_BYTES
    val cacheLimitMb = when {
        cacheUnlimited -> CACHE_UNLIMITED_STOP_MB
        cacheDisabled -> 0
        else -> (cacheLimitBytes / (1024 * 1024)).toInt()
    }
    val unlimitedLabel = stringResource(R.string.song_cache_unlimited)
    val disabledLabel = stringResource(R.string.song_cache_disabled)
    val cacheLimitLabel = when {
        cacheDisabled -> disabledLabel
        cacheUnlimited -> unlimitedLabel
        else -> formatCacheSize(cacheLimitMb)
    }

    SearchableSettingsGroup(search, header = stringResource(R.string.storage)) {
        val showCacheFolderTitle = stringResource(R.string.show_cache_folder)
        row(showCacheFolderTitle, "cache", "cached songs", "library", "folder") {
            SettingsRow(
                icon = SfIcons.Cached,
                title = showCacheFolderTitle,
                subtitle = stringResource(R.string.show_cache_folder_subtitle),
                trailing = {
                    IosSwitch(
                        checked = showCacheFolder,
                        onCheckedChange = AppSettings::setShowCacheFolder,
                    )
                },
                onClick = { AppSettings.setShowCacheFolder(!showCacheFolder) },
            )
        }
        val songCacheLimitTitle = stringResource(R.string.song_cache_limit)
        row(songCacheLimitTitle, "cache", "space", "unlimited") {
            SliderRow(
                icon = SfIcons.Storage,
                title = songCacheLimitTitle,
                subtitle = when {
                    cacheDisabled -> stringResource(R.string.song_cache_disabled_subtitle)
                    cacheUnlimited -> stringResource(R.string.song_cache_unlimited_subtitle)
                    cacheLimitMb > CACHE_WARNING_MB ->
                        stringResource(R.string.song_cache_large_subtitle, cacheLimitLabel)
                    else -> stringResource(R.string.song_cache_limit_subtitle)
                },
                value = cacheLimitLabel,
                sliderValue = if (cacheLimitMb == 0) CACHE_STEP_MB * 0.1f else cacheLimitMb.toFloat(),
                onSliderValue = { raw ->
                    val snapped = (raw / CACHE_STEP_MB + 0.5f).toInt() * CACHE_STEP_MB
                    AppSettings.setAudioCacheLimitBytes(snapped.toLong() * 1024 * 1024)
                },
                valueRange = 0f..CACHE_UNLIMITED_STOP_MB.toFloat(),
                steps = 21,
            )
        }
        val losslessCacheTitle = stringResource(R.string.lossless_cache)
        row(losslessCacheTitle, "cache", "flac", "hi-res", "lossless") {
            SettingsRow(
                icon = SfIcons.HighQuality,
                title = losslessCacheTitle,
                subtitle = stringResource(R.string.lossless_cache_subtitle),
                value = losslessCacheLevel.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.LOSSLESS_CACHE) },
            )
        }
        if (losslessCacheLevel != LosslessCacheLevel.OFF) {
            val losslessLimitMb = (losslessCacheLimitBytes / (1024 * 1024)).toInt()
            val losslessLimitTitle = stringResource(R.string.lossless_cache_limit)
            row(losslessLimitTitle, "cache", "space", "lossless") {
                SliderRow(
                    icon = SfIcons.Storage,
                    title = losslessLimitTitle,
                    subtitle = stringResource(R.string.lossless_cache_limit_subtitle),
                    value = formatCacheSize(losslessLimitMb),
                    sliderValue = losslessLimitMb.toFloat(),
                    onSliderValue = {
                        AppSettings.setLosslessCacheLimitBytes(it.roundToInt().toLong() * 1024 * 1024)
                    },
                    valueRange = (AppSettings.MIN_LOSSLESS_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat()..
                        (AppSettings.MAX_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat(),
                    steps = 38,
                )
            }
        }
        val improveCachedSongsTitle = stringResource(R.string.improve_cached_songs)
        row(improveCachedSongsTitle, "cache", "quality", "upgrade", "wi-fi") {
            SettingsRow(
                icon = SfIcons.Upgrade,
                title = improveCachedSongsTitle,
                subtitle = stringResource(R.string.improve_cached_songs_subtitle),
                trailing = {
                    IosSwitch(
                        checked = improveCachedSongs,
                        onCheckedChange = AppSettings::setImproveCachedSongs,
                    )
                },
                onClick = { AppSettings.setImproveCachedSongs(!improveCachedSongs) },
            )
        }
        val smartPreloadTitle = stringResource(R.string.smart_preload)
        row(smartPreloadTitle, "cache", "pause", "preload", "lossless", "next", "queue") {
            SettingsRow(
                icon = SfIcons.Download,
                title = smartPreloadTitle,
                subtitle = stringResource(R.string.smart_preload_subtitle),
                trailing = {
                    IosSwitch(
                        checked = smartPreload,
                        onCheckedChange = AppSettings::setSmartPreload,
                    )
                },
                onClick = { AppSettings.setSmartPreload(!smartPreload) },
            )
        }
        val clearSongCacheTitle = stringResource(R.string.clear_song_cache)
        row(clearSongCacheTitle, "cache", "free space") {
            SettingsRow(
                icon = SfIcons.DeleteSweep,
                title = clearSongCacheTitle,
                subtitle = stringResource(R.string.clear_song_cache_subtitle),
                onClick = { confirmClearSongCache = true },
            )
        }
        val clearImageCacheTitle = stringResource(R.string.clear_image_cache)
        row(clearImageCacheTitle, "cache", "artwork", "free space") {
            SettingsRow(
                icon = SfIcons.DeleteSweep,
                title = clearImageCacheTitle,
                subtitle = stringResource(R.string.clear_image_cache_subtitle),
                onClick = {
                    val loader = SingletonImageLoader.get(context)
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                    Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    if (confirmClearSongCache) {
        AlertDialog(
            onDismissRequest = { confirmClearSongCache = false },
            title = { Text(stringResource(R.string.clear_song_cache_title)) },
            text = { Text(stringResource(R.string.clear_song_cache_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearSongCache = false
                    AudioCache.clear {
                        Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearSongCache = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun SettingsYourDataSection(
    search: SettingsSearch,
    onOpenReplay: () -> Unit,
) {
    val context = LocalContext.current
    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()
    var exportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var importStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    val backupScope = rememberCoroutineScope()

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            exportStatus = Backup.exportTo(context, target).fold(
                onSuccess = { months ->
                    context.getString(R.string.export_succeeded, context.countOfMonths(months))
                },
                onFailure = {
                    context.getString(R.string.export_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            importStatus = Backup.importFrom(context, source).fold(
                onSuccess = {
                    context.getString(R.string.import_succeeded, context.countOfMonths(it.months), it.from)
                },
                onFailure = {
                    context.getString(R.string.import_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }

    SearchableSettingsGroup(search, header = stringResource(R.string.your_data)) {
        val replayTitle = stringResource(R.string.replay)
        row(replayTitle, "stats", "history", "wrapped") {
            SettingsRow(
                icon = SfIcons.BarChart,
                title = replayTitle,
                subtitle = stringResource(R.string.replay_subtitle),
                onClick = onOpenReplay,
            )
        }
        val workOutGenresTitle = stringResource(R.string.work_out_genres)
        row(workOutGenresTitle, "genre", "replay") {
            SettingsRow(
                icon = SfIcons.LocalOffer,
                title = workOutGenresTitle,
                subtitle = if (replayGenres) {
                    stringResource(R.string.replay_genres_enabled_subtitle)
                } else {
                    stringResource(R.string.replay_genres_disabled_subtitle)
                },
                trailing = {
                    IosSwitch(
                        checked = replayGenres,
                        onCheckedChange = AppSettings::setReplayGenres,
                    )
                },
                onClick = { AppSettings.setReplayGenres(!replayGenres) },
            )
        }
        val exportDataTitle = stringResource(R.string.export_data)
        row(exportDataTitle, "backup") {
            SettingsRow(
                icon = SfIcons.FileUpload,
                title = exportDataTitle,
                subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                onClick = { exportPicker.launch(Backup.suggestedName()) },
            )
        }
        val importDataTitle = stringResource(R.string.import_data)
        row(importDataTitle, "backup", "restore") {
            SettingsRow(
                icon = SfIcons.FileDownload,
                title = importDataTitle,
                subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                onClick = { confirmImport = true },
            )
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(stringResource(R.string.import_backup_title)) },
            text = { Text(stringResource(R.string.import_backup_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) {
                    Text(stringResource(R.string.choose_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
