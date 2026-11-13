package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
import java.util.Locale

@Composable
internal fun SettingsContentPreferencesSection(search: SettingsSearch) {
    val preferMusicOnly by AppSettings.preferMusicOnly.collectAsStateWithLifecycle()
    val filterMusicVideosInAutoplay by AppSettings.filterMusicVideosInAutoplay.collectAsStateWithLifecycle()
    val hideRecents by AppSettings.hideRecents.collectAsStateWithLifecycle()
    val hideHomeVideos by AppSettings.hideHomeVideos.collectAsStateWithLifecycle()
    val hideTopPicks by AppSettings.hideTopPicks.collectAsStateWithLifecycle()
    val hideHomeMoods by AppSettings.hideHomeMoods.collectAsStateWithLifecycle()
    val squareVideoThumbnails by AppSettings.squareVideoThumbnails.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.content_preferences)) {
        val preferMusicOnlyTitle = stringResource(R.string.prefer_music_only)
        row(preferMusicOnlyTitle, "video", "audio", "music video", "convert", "song") {
            SettingsRow(
                icon = SfIcons.Video,
                title = preferMusicOnlyTitle,
                subtitle = stringResource(R.string.prefer_music_only_subtitle),
                trailing = {
                    IosSwitch(
                        checked = preferMusicOnly,
                        onCheckedChange = AppSettings::setPreferMusicOnly,
                    )
                },
                onClick = { AppSettings.setPreferMusicOnly(!preferMusicOnly) },
            )
        }
        val filterMusicVideosTitle = stringResource(R.string.filter_music_videos_autoplay)
        row(filterMusicVideosTitle, "autoplay", "radio", "video", "music video", "suggestions") {
            SettingsRow(
                icon = SfIcons.FilterAlt,
                title = filterMusicVideosTitle,
                subtitle = stringResource(R.string.filter_music_videos_autoplay_subtitle),
                trailing = {
                    IosSwitch(
                        checked = filterMusicVideosInAutoplay,
                        onCheckedChange = AppSettings::setFilterMusicVideosInAutoplay,
                    )
                },
                onClick = { AppSettings.setFilterMusicVideosInAutoplay(!filterMusicVideosInAutoplay) },
            )
        }
        val hideRecentsTitle = stringResource(R.string.hide_recents)
        row(hideRecentsTitle, "recents", "recent", "history", "feed", "home") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideRecentsTitle,
                trailing = {
                    IosSwitch(
                        checked = hideRecents,
                        onCheckedChange = AppSettings::setHideRecents,
                    )
                },
                onClick = { AppSettings.setHideRecents(!hideRecents) },
            )
        }
        val hideHomeVideosTitle = stringResource(R.string.hide_home_videos)
        row(hideHomeVideosTitle, "video", "music video", "feed", "home") {
            SettingsRow(
                icon = SfIcons.VisibilityOff,
                title = hideHomeVideosTitle,
                subtitle = stringResource(R.string.hide_home_videos_subtitle),
                trailing = {
                    IosSwitch(
                        checked = hideHomeVideos,
                        onCheckedChange = AppSettings::setHideHomeVideos,
                    )
                },
                onClick = { AppSettings.setHideHomeVideos(!hideHomeVideos) },
            )
        }
        val hideTopPicksTitle = stringResource(R.string.hide_top_picks)
        row(hideTopPicksTitle, "top picks", "recommendations", "feed", "home") {
            SettingsRow(
                icon = SfIcons.AutoAwesome,
                title = hideTopPicksTitle,
                trailing = {
                    IosSwitch(
                        checked = hideTopPicks,
                        onCheckedChange = AppSettings::setHideTopPicks,
                    )
                },
                onClick = { AppSettings.setHideTopPicks(!hideTopPicks) },
            )
        }
        val hideHomeMoodsTitle = stringResource(R.string.hide_home_moods)
        row(hideHomeMoodsTitle, "mood", "moods", "find your mood", "feed", "home") {
            SettingsRow(
                icon = SfIcons.Star,
                title = hideHomeMoodsTitle,
                trailing = {
                    IosSwitch(
                        checked = hideHomeMoods,
                        onCheckedChange = AppSettings::setHideHomeMoods,
                    )
                },
                onClick = { AppSettings.setHideHomeMoods(!hideHomeMoods) },
            )
        }
        val squareVideoThumbnailsTitle = stringResource(R.string.square_video_thumbnails)
        row(squareVideoThumbnailsTitle, "video", "thumbnail", "cover", "artwork", "player") {
            SettingsRow(
                icon = SfIcons.Apps,
                title = squareVideoThumbnailsTitle,
                subtitle = stringResource(R.string.square_video_thumbnails_subtitle),
                trailing = {
                    IosSwitch(
                        checked = squareVideoThumbnails,
                        onCheckedChange = AppSettings::setSquareVideoThumbnails,
                    )
                },
                onClick = { AppSettings.setSquareVideoThumbnails(!squareVideoThumbnails) },
            )
        }
    }
}

@Composable
internal fun SettingsAppearanceSection(
    search: SettingsSearch,
    windowWidth: Dp,
    onOpenChoice: (SettingsChoiceKind) -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onEditSignatureApi: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
) {
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val appFont by AppSettings.appFont.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val navLabels by AppSettings.showNavLabels.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val disableBlendEffects by AppSettings.disableBlendEffects.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val progressiveBlur by AppSettings.progressiveBlur.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val playerTheme by AppSettings.playerTheme.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val signatureApiEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureApiUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricAboveScrubber by AppSettings.lyricAboveScrubber.collectAsStateWithLifecycle()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()

    SearchableSettingsGroup(search, header = stringResource(R.string.appearance)) {
        val themeTitle = stringResource(R.string.theme)
        row(themeTitle, "dark mode", "light mode") {
            SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
            SegmentedControl(
                options = ThemeMode.entries.map { it.localizedLabel() },
                selectedIndex = ThemeMode.entries.indexOf(theme),
                onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val fontTitle = stringResource(R.string.font)
        row(fontTitle, "typeface", "sf pro", "legacy") {
            SettingsRow(
                icon = SfIcons.Font,
                title = fontTitle,
                subtitle = appFont.description(),
                value = appFont.localizedLabel(),
                onClick = { onOpenChoice(SettingsChoiceKind.FONT) },
            )
        }
        val reduceAnimationTitle = stringResource(R.string.reduce_animation)
        row(reduceAnimationTitle, "motion") {
            SettingsRow(
                icon = SfIcons.MotionPhotosOff,
                title = reduceAnimationTitle,
                subtitle = stringResource(R.string.reduce_animation_subtitle),
                trailing = {
                    IosSwitch(
                        checked = reduceAnimation,
                        onCheckedChange = AppSettings::setReduceAnimation,
                    )
                },
                onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
            )
        }
        val navLabelsTitle = stringResource(R.string.show_nav_labels)
        row(navLabelsTitle, "navigation", "tabs", "bar", "labels", "icons") {
            SettingsRow(
                icon = SfIcons.Font,
                title = navLabelsTitle,
                subtitle = stringResource(R.string.show_nav_labels_subtitle),
                trailing = {
                    IosSwitch(
                        checked = navLabels,
                        onCheckedChange = AppSettings::setShowNavLabels,
                    )
                },
                onClick = { AppSettings.setShowNavLabels(!navLabels) },
            )
        }
        val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
        row(reduceDynamicBlurTitle, "blur", "performance") {
            SettingsRow(
                icon = SfIcons.BlurOff,
                title = reduceDynamicBlurTitle,
                subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                trailing = {
                    IosSwitch(
                        checked = reduceDynamicBlur,
                        onCheckedChange = AppSettings::setReduceDynamicBlur,
                    )
                },
                onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
            )
        }
        val disableBlendEffectsTitle = stringResource(R.string.disable_blend_effects)
        row(disableBlendEffectsTitle, "blend", "blending", "colour", "color", "tint", "player", "menu") {
            SettingsRow(
                icon = SfIcons.Gradient,
                title = disableBlendEffectsTitle,
                subtitle = stringResource(R.string.disable_blend_effects_subtitle),
                trailing = {
                    IosSwitch(
                        checked = disableBlendEffects,
                        onCheckedChange = AppSettings::setDisableBlendEffects,
                    )
                },
                onClick = { AppSettings.setDisableBlendEffects(!disableBlendEffects) },
            )
        }
        val liquidGlassTitle = stringResource(R.string.liquid_glass)
        row(liquidGlassTitle, "glass", "blur") {
            SettingsRow(
                icon = SfIcons.AutoAwesome,
                title = liquidGlassTitle,
                subtitle = stringResource(
                    if (liquidGlassSupported) {
                        R.string.liquid_glass_subtitle
                    } else {
                        R.string.liquid_glass_unavailable
                    },
                ),
                enabled = liquidGlassSupported,
                trailing = {
                    IosSwitch(
                        checked = liquidGlass,
                        onCheckedChange = AppSettings::setLiquidGlass,
                        enabled = liquidGlassSupported,
                    )
                },
                onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
            )
        }
        if (liquidGlass && liquidGlassSupported) {
            val glassPerformanceTitle = stringResource(R.string.glass_performance)
            row(glassPerformanceTitle, "glass", "performance", "refraction", "frosted", "lag", divided = false) {
                SettingsSubRow(
                    title = glassPerformanceTitle,
                    checked = glassPerformance,
                    onCheckedChange = AppSettings::setGlassPerformance,
                )
            }
            val progressiveBlurTitle = stringResource(R.string.progressive_blur)
            row(progressiveBlurTitle, "blur", "status bar", "top", divided = false) {
                SettingsSubRow(
                    title = progressiveBlurTitle,
                    checked = progressiveBlur,
                    onCheckedChange = AppSettings::setProgressiveBlur,
                )
            }
        }
        if (fullBleedArtworkAvailable(windowWidth)) {
            val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
            row(fullScreenCoverArtTitle, "artwork", "player") {
                SettingsRow(
                    icon = SfIcons.Fullscreen,
                    title = fullScreenCoverArtTitle,
                    subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = fullBleedArtwork,
                            onCheckedChange = AppSettings::setFullBleedArtwork,
                        )
                    },
                    onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                )
            }
        }
        val playerThemeTitle = stringResource(R.string.player_theme)
        row(playerThemeTitle, "background", "player", "mesh", "gradient", "legacy", "simple", "blur") {
            SettingsRow(
                icon = SfIcons.Gradient,
                title = playerThemeTitle,
                subtitle = playerTheme.description(),
            )
            SegmentedControl(
                options = PlayerTheme.entries.map { it.localizedLabel() },
                selectedIndex = PlayerTheme.entries.indexOf(playerTheme),
                onSelect = { AppSettings.setPlayerTheme(PlayerTheme.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
        row(animatedCoverArtTitle, "canvas", "video", "spotify") {
            SettingsRow(
                icon = SfIcons.Animation,
                title = animatedCoverArtTitle,
                subtitle = stringResource(R.string.animated_cover_art_subtitle),
                trailing = {
                    IosSwitch(
                        checked = animatedCanvas,
                        onCheckedChange = AppSettings::setAnimatedCanvas,
                    )
                },
                onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
            )
        }
        if (animatedCanvas) {
            val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
            row(coverCellularTitle, "canvas", "cellular", divided = false) {
                SettingsSubRow(
                    title = coverCellularTitle,
                    checked = canvasOverCellular,
                    onCheckedChange = AppSettings::setCanvasOverCellular,
                )
            }
            val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
            row(spotifyCanvasTitle, "spotify", "canvas", divided = false) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSpotifyCanvasAuth)
                        .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = spotifyCanvasTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Chevron()
                }
            }
        }
        val artistSignaturesTitle = stringResource(R.string.artist_signatures)
        row(artistSignaturesTitle, "signature", "artist", "logo") {
            SettingsRow(
                icon = SfIcons.Draw,
                title = artistSignaturesTitle,
                subtitle = stringResource(R.string.artist_signatures_subtitle),
                trailing = {
                    IosSwitch(
                        checked = signatureApiEnabled,
                        onCheckedChange = AppSettings::setSignatureApiEnabled,
                    )
                },
                onClick = { AppSettings.setSignatureApiEnabled(!signatureApiEnabled) },
            )
        }
        if (signatureApiEnabled) {
            val signatureUrlTitle = stringResource(R.string.artist_signatures_url)
            row(signatureUrlTitle, "signature", "api", "url", divided = false) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEditSignatureApi)
                        .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = signatureApiUrl.ifBlank {
                            stringResource(R.string.artist_signatures_unconfigured)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Chevron()
                }
            }
        }
        val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
        row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch") {
            SettingsRow(
                icon = SfIcons.Notes,
                title = syncedLyricsTitle,
                subtitle = stringResource(R.string.synced_lyrics_subtitle),
                trailing = {
                    IosSwitch(
                        checked = syncedLyrics,
                        onCheckedChange = AppSettings::setSyncedLyrics,
                    )
                },
                onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
            )
        }
        if (syncedLyrics) {
            val lyricAboveScrubberTitle = stringResource(R.string.lyric_above_scrubber)
            row(lyricAboveScrubberTitle, "lyrics", "scrubber", "timeline", "strip") {
                SettingsRow(
                    icon = SfIcons.Draw,
                    title = lyricAboveScrubberTitle,
                    subtitle = stringResource(R.string.lyric_above_scrubber_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = lyricAboveScrubber,
                            onCheckedChange = AppSettings::setLyricAboveScrubber,
                        )
                    },
                    onClick = { AppSettings.setLyricAboveScrubber(!lyricAboveScrubber) },
                )
            }
            val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
            row(lyricsBlurTitle, "lyrics", "blur") {
                SettingsRow(
                    icon = SfIcons.BlurOn,
                    title = lyricsBlurTitle,
                    subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                    trailing = {
                        IosSwitch(
                            checked = lyricsBlur,
                            onCheckedChange = AppSettings::setLyricsBlur,
                        )
                    },
                    onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                )
            }
            val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
            row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
                SettingsRow(
                    icon = SfIcons.Language,
                    title = lyricsSourcesTitle,
                    subtitle = lyricsSources
                        .sortedBy { it.ordinal }
                        .joinToString(", ") { it.label }
                        .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                    trailing = { Chevron() },
                    onClick = onLyricsSources,
                )
            }
            val translationLanguageTitle = stringResource(R.string.translation_language)
            row(translationLanguageTitle, "lyrics", "translate") {
                SettingsRow(
                    icon = SfIcons.Translate,
                    title = translationLanguageTitle,
                    subtitle = if (translationLanguage.isBlank()) {
                        stringResource(R.string.translation_language_subtitle)
                    } else {
                        translationLanguageName(
                            translationLanguage,
                            AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                        )
                    },
                    trailing = { Chevron() },
                    onClick = onTranslationLanguage,
                )
            }
        }
    }
}

@Composable
internal fun SettingsPerformanceSection(search: SettingsSearch) {
    val context = LocalContext.current
    val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
    val currentDisplay = LocalView.current.display
    val supportedRefreshRates = remember(currentDisplay) {
        currentDisplay.supportedPerformanceRefreshRates()
    }
    val selectedPerformanceRefreshRate = remember(currentDisplay, performanceRefreshRate) {
        currentDisplay.resolvePerformanceRefreshRate(performanceRefreshRate)
    }

    LaunchedEffect(selectedPerformanceRefreshRate, performanceRefreshRate) {
        if (selectedPerformanceRefreshRate != performanceRefreshRate) {
            AppSettings.setPerformanceRefreshRate(selectedPerformanceRefreshRate)
        }
    }

    var showPerformanceWarning by remember { mutableStateOf(false) }
    var showPerformanceConfirmation by remember { mutableStateOf(false) }
    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showPerformanceConfirmation = true
    }

    SearchableSettingsGroup(search, header = stringResource(R.string.performance)) {
        val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
        row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth") {
            SettingsRow(
                icon = SfIcons.Performance,
                title = highPerformanceModeTitle,
                subtitle = if (highPerformanceMode) {
                    stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                } else {
                    stringResource(R.string.high_performance_subtitle)
                },
                trailing = {
                    IosSwitch(
                        checked = highPerformanceMode,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showPerformanceWarning = true
                            } else {
                                AppSettings.setHighPerformanceMode(false)
                            }
                        },
                    )
                },
                onClick = {
                    if (highPerformanceMode) {
                        AppSettings.setHighPerformanceMode(false)
                    } else {
                        showPerformanceWarning = true
                    }
                },
            )
        }
        if (highPerformanceMode) {
            val refreshRateTitle = stringResource(R.string.refresh_rate)
            row(refreshRateTitle, "hz", "frame rate") {
                SettingsRow(
                    icon = SfIcons.FrameRate,
                    title = refreshRateTitle,
                )
                SegmentedControl(
                    options = supportedRefreshRates.map { "$it Hz" },
                    selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                    onSelect = { index ->
                        AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                    },
                    modifier = Modifier.padding(
                        start = TEXT_INSET,
                        end = ROW_INSET,
                        bottom = 14.dp,
                    ),
                )
            }
        }
    }

    if (showPerformanceWarning) {
        AlertDialog(
            onDismissRequest = { showPerformanceWarning = false },
            title = { Text(stringResource(R.string.high_performance_before_enabling)) },
            text = { Text(stringResource(R.string.high_performance_battery_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPerformanceWarning = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        val settingsIntent = intent.takeIf {
                            it.resolveActivity(context.packageManager) != null
                        } ?: Intent(Settings.ACTION_SETTINGS)
                        batterySettingsLauncher.launch(settingsIntent)
                    },
                ) {
                    Text(stringResource(R.string.open_battery_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerformanceWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPerformanceConfirmation) {
        AlertDialog(
            onDismissRequest = { showPerformanceConfirmation = false },
            title = { Text(stringResource(R.string.enable_high_performance_title)) },
            text = { Text(stringResource(R.string.enable_high_performance_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPerformanceConfirmation = false
                        AppSettings.setHighPerformanceMode(true)
                    },
                ) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerformanceConfirmation = false }) {
                    Text(stringResource(R.string.not_yet))
                }
            },
        )
    }
}
