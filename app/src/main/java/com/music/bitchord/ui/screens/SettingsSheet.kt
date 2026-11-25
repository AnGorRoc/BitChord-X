package com.music.bitchord.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LosslessDataNotice
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.wear.WatchInstaller

/**
 * Grouped settings, in the shape phones have taught people to expect: inset
 * cards of rows, a leading glyph per row, the current value on the right, and a
 * plain-language footer under any group whose effect isn't obvious from its
 * title. Anything with more than two choices opens a sheet rather than pushing
 * a row of chips into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The window's width, for the gates that depend on it. */
    windowWidth: Dp,
    signedIn: Boolean,
    account: Account?,
    onAccountScrobbling: () -> Unit,
    onEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    /** Opens [DriveBackupScreen], under Backup and Restore. */
    onDriveBackup: () -> Unit = {},
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onSources: () -> Unit,
    onListenTogether: () -> Unit,
    onWearOs: () -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onAppLanguage: () -> Unit,
    onEditSignatureApi: () -> Unit,
    /** Asks before a DAC with no settable volume may play at full scale. */
    onConfirmFixedVolume: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /**
     * The category this page is, or null for the main page — which lists the
     * categories rather than their rows, until something is typed into its
     * search field.
     */
    section: SettingsCategory? = null,
    /** Opens one category as a page of its own. */
    onOpenSection: (SettingsCategory) -> Unit = {},
    /** Opens a choice with more options than its row can show, as a page. */
    onOpenChoice: (SettingsChoiceKind) -> Unit = {},
    /** Opens the person's feedback: their reports and the developer's answers. */
    onFeedback: () -> Unit = {},
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val openCellularQuality by LosslessDataNotice.openCellularQuality.collectAsStateWithLifecycle()
    LaunchedEffect(openCellularQuality) {
        if (openCellularQuality) {
            onOpenChoice(SettingsChoiceKind.AUDIO_QUALITY_CELLULAR)
            LosslessDataNotice.cellularQualityHandled()
        }
    }

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    val search = SettingsSearch(if (section == null) searchQuery else "", inPage = section != null)
    val searching = section == null && searchQuery.isNotBlank()
    fun shows(category: SettingsCategory) = section == category || searching
    val watchAvailable = remember { WatchInstaller.available(context) }
    val userName by AppSettings.userName.collectAsStateWithLifecycle()
    val defaultUserLabel = stringResource(R.string.default_user_name)
    val resolvedUserName = remember(userName, account?.name, defaultUserLabel) {
        userName.trim().takeIf { it.isNotBlank() }
            ?: account?.name?.trim()?.takeIf { it.isNotBlank() }
            ?: defaultUserLabel
    }

    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    var isFirstSearchComposition by remember { mutableStateOf(true) }
    LaunchedEffect(searchQuery) {
        if (isFirstSearchComposition) {
            isFirstSearchComposition = false
        } else {
            isFirstSearchComposition = false
            scrollState.scrollTo(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(contentPadding),
    ) {
        // About is its own page, with BitChord X itself as its heading.
        if (section == SettingsCategory.ABOUT) {
            SettingsAboutPage(version)
            return@Column
        }
        SettingsPageTitle(stringResource(section?.title ?: R.string.settings))
        if (section == null) {
            SearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSubmit = {},
                placeholder = stringResource(R.string.settings_search_hint),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )

            if (!searching) {
                SettingsQuickSettingsGroup(onOpenSection = onOpenSection)
            }

            SettingsAccountAndPartyGroup(
                search = search,
                signedIn = signedIn,
                account = account,
                onAccountScrobbling = onAccountScrobbling,
                onListenTogether = onListenTogether,
            )

            if (!searching) {
                SettingsCategoryList(onOpen = onOpenSection)
                if (watchAvailable) {
                    SettingsGroup {
                        WearOsSettingsRow(onClick = onWearOs)
                    }
                }
            }
        }

        if (shows(SettingsCategory.AUDIO_QUALITY)) {
            SettingsAudioQualityGroup(
                search = search,
                onSources = onSources,
                onOpenChoice = onOpenChoice,
            )
        }
        if (shows(SettingsCategory.APPEARANCE)) {
            SettingsAppearanceSection(search = search, onOpenChoice = onOpenChoice)
        }
        if (shows(SettingsCategory.EFFECTS_PERFORMANCE)) {
            SettingsEffectsSection(search = search)
            SettingsPerformanceSection(search = search)
        }
        if (shows(SettingsCategory.PLAYER)) {
            SettingsPlayerSection(
                search = search,
                windowWidth = windowWidth,
                onSpotifyCanvasAuth = onSpotifyCanvasAuth,
                onEditSignatureApi = onEditSignatureApi,
            )
        }
        if (shows(SettingsCategory.LYRICS)) {
            SettingsLyricsSection(
                search = search,
                onLyricsSources = onLyricsSources,
                onTranslationLanguage = onTranslationLanguage,
            )
        }
        if (shows(SettingsCategory.PLAYBACK)) {
            SettingsPlaybackSection(search = search, onOpenChoice = onOpenChoice)
        }
        if (shows(SettingsCategory.SOUND)) {
            SettingsSoundSection(search = search, onEqualizer = onEqualizer)
        }
        if (shows(SettingsCategory.OUTPUT)) {
            SettingsOutputSection(
                search = search,
                onOpenChoice = onOpenChoice,
                onConfirmFixedVolume = onConfirmFixedVolume,
            )
        }
        if (shows(SettingsCategory.CONTENT_PREFERENCES)) {
            SettingsContentPreferencesSection(search = search)
        }
        if (shows(SettingsCategory.DOWNLOADS)) {
            SettingsDownloadsSection(search = search, onOpenChoice = onOpenChoice)
        }
        if (shows(SettingsCategory.STORAGE)) {
            SettingsStorageSection(search = search, onOpenChoice = onOpenChoice)
        }
        if (shows(SettingsCategory.LOCAL_MUSIC)) {
            SettingsLocalMusicSection(search = search)
        }
        // Lives on the Listen Together page; here only so a search still finds it.
        if (searching) {
            SettingsListenTogetherDeviceSection(search = search)
        }

        if (searching && watchAvailable) {
            SearchableSettingsGroup(search) {
                row(stringResource(R.string.wear_os_section), "watch", "wear os", "galaxy watch", "pixel watch", "install") {
                    WearOsSettingsRow(onClick = onWearOs)
                }
            }
        }

        if (shows(SettingsCategory.PRIVACY_DATA)) {
            SettingsYourDataSection(
                search = search,
                onOpenReplay = onOpenReplay,
                onDriveBackup = onDriveBackup,
            )
        }
        if (shows(SettingsCategory.LANGUAGE)) {
            SettingsLanguageSection(search = search, onAppLanguage = onAppLanguage)
        }
        if (shows(SettingsCategory.ADVANCED)) {
            SettingsAdvancedSection(search = search)
        }

        if (searching && !search.anyMatch) {
            MessageState(stringResource(R.string.settings_search_empty, searchQuery))
        }

        if (section == null && searchQuery.isBlank()) {
            FeedbackSettingsGroup(accountName = resolvedUserName, onClick = onFeedback)
            SettingsAboutRow(version = version, onClick = { onOpenSection(SettingsCategory.ABOUT) })
            Spacer(Modifier.height(8.dp))
        }
    }
}
