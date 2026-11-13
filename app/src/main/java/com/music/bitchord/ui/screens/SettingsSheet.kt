package com.music.bitchord.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.BitPerfectMode
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
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onAccountScrobbling: () -> Unit,
    onEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onSources: () -> Unit,
    onListenTogether: () -> Unit,
    onWearOs: () -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onAppLanguage: () -> Unit,
    onEditSignatureApi: () -> Unit,
    /**
     * Asks before [mode] is turned on: it hands volume to the DAC at full
     * scale. Shown in the app's frosted alert, which needs the app's blur.
     */
    onConfirmBitPerfect: (BitPerfectMode) -> Unit,
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
        SettingsPageTitle(stringResource(section?.title ?: R.string.settings))
        if (section == null) {
            SearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSubmit = {},
                placeholder = stringResource(R.string.settings_search_hint),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )

            SettingsAccountAndPartyGroup(
                search = search,
                signedIn = signedIn,
                account = account,
                onAccountScrobbling = onAccountScrobbling,
                onListenTogether = onListenTogether,
            )

            SettingsAudioQualityGroup(
                search = search,
                onSources = onSources,
                onOpenChoice = onOpenChoice,
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

        if (shows(SettingsCategory.DOWNLOADS)) {
            SettingsDownloadsSection(search = search, onOpenChoice = onOpenChoice)
        }
        if (shows(SettingsCategory.PLAYBACK)) {
            SettingsPlaybackSection(
                search = search,
                onOpenChoice = onOpenChoice,
                onConfirmFixedVolume = onConfirmFixedVolume,
                onEqualizer = onEqualizer,
            )
        }
        if (shows(SettingsCategory.CONTENT_PREFERENCES)) {
            SettingsContentPreferencesSection(search = search)
        }
        if (shows(SettingsCategory.APPEARANCE)) {
            SettingsAppearanceSection(
                search = search,
                windowWidth = windowWidth,
                onOpenChoice = onOpenChoice,
                onSpotifyCanvasAuth = onSpotifyCanvasAuth,
                onEditSignatureApi = onEditSignatureApi,
                onLyricsSources = onLyricsSources,
                onTranslationLanguage = onTranslationLanguage,
            )
        }
        if (shows(SettingsCategory.PERFORMANCE)) {
            SettingsPerformanceSection(search = search)
        }
        if (shows(SettingsCategory.LOCAL_MUSIC)) {
            SettingsLocalMusicSection(search = search)
        }
        if (shows(SettingsCategory.STORAGE)) {
            SettingsStorageSection(search = search, onOpenChoice = onOpenChoice)
        }

        if (searching && watchAvailable) {
            SearchableSettingsGroup(search) {
                row(stringResource(R.string.wear_os_section), "watch", "wear os", "galaxy watch", "pixel watch", "install") {
                    WearOsSettingsRow(onClick = onWearOs)
                }
            }
        }

        if (shows(SettingsCategory.YOUR_DATA)) {
            SettingsYourDataSection(search = search, onOpenReplay = onOpenReplay)
        }
        if (shows(SettingsCategory.MISCELLANEOUS)) {
            SettingsMiscellaneousSection(search = search)
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
            Text(
                text = buildAnnotatedString {
                    append("bitchord $version  ")
                    val linkStyles = TextLinkStyles(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    withLink(LinkAnnotation.Url("https://github.com/AnGorRoc/BitChord-X", linkStyles)) {
                        append("GitHub")
                    }
                    append("  ")
                    withLink(LinkAnnotation.Url("https://github.com/AnGorRoc", linkStyles)) {
                        append("Developer")
                    }
                    append("  ")
                    withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx/BitChord", linkStyles)) {
                        append("Original project")
                    }
                    append("\n~YouTube Music & Listen Together Backend")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
            )
        }
    }
}
