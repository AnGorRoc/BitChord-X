package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SpotifyImportTrack
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

val DIALOG_DARK_GREY = Color(0xFF141416)
val DIALOG_LIGHT_GREY = Color(0xFFF6F6F7)
const val DIALOG_FROST_ALPHA = 0.86f
const val DIALOG_LIGHT_FROST_ALPHA = 0.88f
val DIALOG_EDGE_WIDTH = 0.5.dp
val DIALOG_EDGE_COLOR = Color.White.copy(alpha = 0.12f)
val DIALOG_LIGHT_EDGE_COLOR = Color.Black.copy(alpha = 0.08f)

fun dialogFrostStyle(fill: Color = DIALOG_DARK_GREY, alpha: Float = DIALOG_FROST_ALPHA): HazeStyle = HazeStyle(
    backgroundColor = fill,
    tints = listOf(HazeTint(fill.copy(alpha = alpha))),
    blurRadius = 24.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint(fill),
)

fun Modifier.dialogFrostBackground(
    hazeState: HazeState?,
    reduceDynamicBlur: Boolean,
    shape: CornerBasedShape = RoundedCornerShape(26.dp),
    fill: Color = DIALOG_DARK_GREY,
    borderColor: Color = DIALOG_EDGE_COLOR,
    alpha: Float = DIALOG_FROST_ALPHA,
): Modifier = shadow(elevation = 20.dp, shape = shape)
    .clip(shape)
    .then(
        if (reduceDynamicBlur || hazeState == null) {
            Modifier.background(fill)
        } else {
            Modifier.optimizedHazeEffect(
                state = hazeState,
                style = dialogFrostStyle(fill, alpha),
            )
        }
    )
    .border(
        width = DIALOG_EDGE_WIDTH,
        color = borderColor,
        shape = shape,
    )


private val IMPORT_LANGUAGES = listOf(
    "No Preference",
    "English",
    "Multilingual",
    "Hindi",
    "Tamil",
    "Telugu",
    "Kannada",
    "Malayalam",
    "Manual / Other",
)

enum class SpotifyImportStep {
    INTRO,
    INPUT,
    MATCHING,
    UNMATCHED,
}

enum class SpotifyImportDestination {
    LOCAL,
    YOUTUBE,
}

/**
 * Modern, comfortable Spotify playlist import dialog with a smooth introductory step
 * explaining the system and offering a clear, frictionless choice between saving locally
 * on the device or directly syncing to YouTube Music.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SpotifyImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    /** Called once the songs are matched; [saveLocally] reflects user destination preference. */
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>, saveLocally: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    var currentStep by remember { mutableStateOf(SpotifyImportStep.INTRO) }
    var destination by remember { mutableStateOf(SpotifyImportDestination.LOCAL) }
    var link by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var selectedLanguage by remember { mutableStateOf("No Preference") }
    var customLanguageInput by remember { mutableStateOf("") }
    var showAdvancedOptions by remember { mutableStateOf(false) }

    var working by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var progressCompleted by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var unmatched by remember { mutableStateOf<List<SpotifyImportTrack>?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        working = false
        statusText = null
        errorMessage = null
        currentStep = SpotifyImportStep.INPUT
    }

    fun startImport() {
        val trimmed = link.trim()
        val id = SpotifyImporter.extractPlaylistId(trimmed)
        if (id == null) {
            errorMessage = context.getString(R.string.spotify_import_invalid)
            return
        }
        val effectiveLanguage = if (selectedLanguage == "Manual / Other") {
            customLanguageInput.trim().ifBlank { null }
        } else if (selectedLanguage == "No Preference") {
            null
        } else {
            selectedLanguage
        }

        errorMessage = null
        working = true
        progressCompleted = 0
        progressTotal = 0
        currentStep = SpotifyImportStep.MATCHING
        statusText = context.getString(R.string.spotify_import_fetching)

        importJob = scope.launch {
            try {
                val (name, tracks) = SpotifyImporter.fetchPlaylistTracks(id)
                if (tracks.isEmpty()) {
                    errorMessage = context.getString(R.string.spotify_import_empty_playlist)
                    currentStep = SpotifyImportStep.INPUT
                    working = false
                    return@launch
                }
                progressTotal = tracks.size
                statusText = context.getString(R.string.spotify_import_matching, 0, tracks.size)

                val (songs, missed) = SpotifyImporter.resolveToSongs(tracks, effectiveLanguage) { done, total ->
                    progressCompleted = done
                    progressTotal = total
                    statusText = context.getString(R.string.spotify_import_matching, done, total)
                }

                if (songs.isEmpty()) {
                    errorMessage = context.getString(R.string.spotify_import_none)
                    currentStep = SpotifyImportStep.INPUT
                    working = false
                    return@launch
                }

                val saveLocally = destination == SpotifyImportDestination.LOCAL || !signedIn
                onImported(name, privacy, songs, saveLocally)

                if (missed.isEmpty()) {
                    onDismiss()
                } else {
                    unmatched = missed
                    currentStep = SpotifyImportStep.UNMATCHED
                }
            } catch (e: CancellationException) {
                // User cancelled import
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.spotify_import_failed, e.message.orEmpty())
                currentStep = SpotifyImportStep.INPUT
            } finally {
                working = false
            }
        }
    }

    val dialogShape = RoundedCornerShape(26.dp)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val dialogFill = if (isDark) DIALOG_DARK_GREY else DIALOG_LIGHT_GREY
    val dialogBorder = if (isDark) DIALOG_EDGE_COLOR else DIALOG_LIGHT_EDGE_COLOR
    val dialogAlpha = if (isDark) DIALOG_FROST_ALPHA else DIALOG_LIGHT_FROST_ALPHA
    val scrimAlpha = if (isDark) 0.55f else 0.28f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { if (!working) onDismiss() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(min = 300.dp, max = 360.dp)
                .fillMaxWidth()
                .dialogFrostBackground(
                    hazeState = hazeState,
                    reduceDynamicBlur = reduceDynamicBlur,
                    shape = dialogShape,
                    fill = dialogFill,
                    borderColor = dialogBorder,
                    alpha = dialogAlpha,
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = 380f,
                    ),
                )
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val floatSpec = spring<Float>(dampingRatio = 0.82f, stiffness = 380f)
                    val intOffsetSpec = spring<IntOffset>(dampingRatio = 0.82f, stiffness = 380f)
                    if (targetState.ordinal > initialState.ordinal) {
                        (slideInHorizontally(intOffsetSpec) { width -> width / 3 } + fadeIn(floatSpec))
                            .togetherWith(slideOutHorizontally(intOffsetSpec) { width -> -width / 3 } + fadeOut(floatSpec))
                    } else {
                        (slideInHorizontally(intOffsetSpec) { width -> -width / 3 } + fadeIn(floatSpec))
                            .togetherWith(slideOutHorizontally(intOffsetSpec) { width -> width / 3 } + fadeOut(floatSpec))
                    }
                },
                label = "SpotifyImportStepTransition",
            ) { step ->
                when (step) {
                    SpotifyImportStep.INTRO -> {
                        IntroStepView(
                            signedIn = signedIn,
                            destination = destination,
                            onDestinationChange = { destination = it },
                            onContinue = { currentStep = SpotifyImportStep.INPUT },
                            onCancel = onDismiss,
                        )
                    }

                    SpotifyImportStep.INPUT -> {
                        InputStepView(
                            link = link,
                            onLinkChange = {
                                link = it
                                if (errorMessage != null) errorMessage = null
                            },
                            destination = destination,
                            onToggleDestination = {
                                if (signedIn) {
                                    destination = if (destination == SpotifyImportDestination.LOCAL) {
                                        SpotifyImportDestination.YOUTUBE
                                    } else {
                                        SpotifyImportDestination.LOCAL
                                    }
                                }
                            },
                            signedIn = signedIn,
                            privacy = privacy,
                            onPrivacyChange = { privacy = it },
                            selectedLanguage = selectedLanguage,
                            onLanguageChange = { selectedLanguage = it },
                            customLanguageInput = customLanguageInput,
                            onCustomLanguageChange = { customLanguageInput = it },
                            showAdvancedOptions = showAdvancedOptions,
                            onToggleAdvancedOptions = { showAdvancedOptions = !showAdvancedOptions },
                            errorMessage = errorMessage,
                            onPaste = {
                                val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                if (clip.isNotBlank()) {
                                    link = clip
                                    errorMessage = null
                                }
                            },
                            onBack = { currentStep = SpotifyImportStep.INTRO },
                            onStartImport = ::startImport,
                            onCancel = onDismiss,
                        )
                    }

                    SpotifyImportStep.MATCHING -> {
                        MatchingStepView(
                            status = statusText ?: stringResource(R.string.spotify_import_fetching),
                            completed = progressCompleted,
                            total = progressTotal,
                            onCancel = ::cancelImport,
                        )
                    }

                    SpotifyImportStep.UNMATCHED -> {
                        UnmatchedStepView(
                            missed = unmatched.orEmpty(),
                            onDone = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

/** Step 1: Onboarding window explaining the import system and selecting destination upfront. */
@Composable
private fun IntroStepView(
    signedIn: Boolean,
    destination: SpotifyImportDestination,
    onDestinationChange: (SpotifyImportDestination) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(Res.drawable.spotify_logo),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(42.dp),
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.spotify_import_intro_title),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Text(
            text = stringResource(R.string.spotify_import_intro_desc),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 17.sp,
            ),
            color = Color.White.copy(alpha = 0.70f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Local Card
            val isLocal = destination == SpotifyImportDestination.LOCAL
            DestinationOptionCard(
                title = stringResource(R.string.spotify_import_target_local_title),
                subtitle = stringResource(R.string.spotify_import_target_local_desc),
                icon = Icons.Rounded.PhoneAndroid,
                selected = isLocal,
                enabled = true,
                onClick = { onDestinationChange(SpotifyImportDestination.LOCAL) },
            )

            // YouTube Card
            val isYoutube = destination == SpotifyImportDestination.YOUTUBE
            DestinationOptionCard(
                title = stringResource(R.string.spotify_import_target_youtube_title),
                subtitle = if (signedIn) {
                    stringResource(R.string.spotify_import_target_youtube_desc)
                } else {
                    stringResource(R.string.spotify_import_target_youtube_login_req)
                },
                icon = Icons.Rounded.Cloud,
                selected = isYoutube && signedIn,
                enabled = signedIn,
                onClick = {
                    if (signedIn) onDestinationChange(SpotifyImportDestination.YOUTUBE)
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
        ) {
            Text(
                text = stringResource(R.string.spotify_import_continue),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.height(4.dp))

        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.spotify_import_cancel),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.60f),
            )
        }
    }
}

/** Card for choosing import destination. */
@Composable
internal fun DestinationOptionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val containerColor = if (selected) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.05f)
    }
    val borderColor = if (selected) {
        Color.White.copy(alpha = 0.40f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(containerColor)
            .border(width = 0.5.dp, color = borderColor, shape = cardShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (selected) Color.White.copy(alpha = 0.20f)
                    else Color.White.copy(alpha = 0.08f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(19.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                ),
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.40f),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                ),
                color = if (enabled) Color.White.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.35f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) Color.White else Color.White.copy(alpha = 0.25f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Step 2: Clean input window with paste button, privacy options, and collapsible language preference. */
@Composable
private fun InputStepView(
    link: String,
    onLinkChange: (String) -> Unit,
    destination: SpotifyImportDestination,
    onToggleDestination: () -> Unit,
    signedIn: Boolean,
    privacy: PlaylistPrivacy,
    onPrivacyChange: (PlaylistPrivacy) -> Unit,
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    customLanguageInput: String,
    onCustomLanguageChange: (String) -> Unit,
    showAdvancedOptions: Boolean,
    onToggleAdvancedOptions: () -> Unit,
    errorMessage: String?,
    onPaste: () -> Unit,
    onBack: () -> Unit,
    onStartImport: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top navigation bar with Back button and Destination badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

            Text(
                text = stringResource(R.string.spotify_import_step_input_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
                color = Color.White,
            )

            // Clickable destination tag pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(enabled = signedIn, onClick = onToggleDestination)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (destination == SpotifyImportDestination.LOCAL) {
                            Icons.Rounded.PhoneAndroid
                        } else {
                            Icons.Rounded.Cloud
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = if (destination == SpotifyImportDestination.LOCAL) {
                            stringResource(R.string.spotify_import_destination_tag_local)
                        } else {
                            stringResource(R.string.spotify_import_destination_tag_youtube)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Link input with inline Paste & Clear actions
        val inputShape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(inputShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(
                    width = 0.5.dp,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error
                    else Color.White.copy(alpha = 0.18f),
                    shape = inputShape,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.spotify_logo),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )

            Spacer(Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            ) {
                if (link.isEmpty()) {
                    Text(
                        text = stringResource(R.string.spotify_import_hint),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = Color.White.copy(alpha = 0.40f),
                    )
                }
                BasicTextField(
                    value = link,
                    onValueChange = onLinkChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (link.isNotBlank()) onStartImport() },
                    ),
                )
            }

            if (link.isNotBlank()) {
                IconButton(
                    onClick = { onLinkChange("") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Clear,
                        contentDescription = stringResource(R.string.spotify_import_clear_tooltip),
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            } else {
                TextButton(
                    onClick = onPaste,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.spotify_import_paste_action),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                }
            }
        }

        // Error message callout
        AnimatedVisibility(visible = errorMessage != null) {
            errorMessage?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Privacy selection for YouTube destination
        if (signedIn && destination == SpotifyImportDestination.YOUTUBE) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.spotify_import_privacy_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlaylistPrivacy.entries.forEach { option ->
                    val selected = option == privacy
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable { onPrivacyChange(option) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            ),
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Collapsible Advanced Matching Options (Eliminates the dirty language clutter!)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleAdvancedOptions)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.spotify_import_advanced_options),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (showAdvancedOptions) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(
            visible = showAdvancedOptions,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.spotify_import_language_preference),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    IMPORT_LANGUAGES.forEach { lang ->
                        val selected = lang == selectedLanguage
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { onLanguageChange(lang) }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = lang,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (selectedLanguage == "Manual / Other") {
                    Spacer(Modifier.height(8.dp))
                    PillTextField(
                        value = customLanguageInput,
                        onValueChange = onCustomLanguageChange,
                        placeholder = stringResource(R.string.spotify_import_language_hint),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.spotify_import_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.60f),
                )
            }

            Button(
                onClick = onStartImport,
                enabled = link.isNotBlank(),
                modifier = Modifier
                    .weight(1.4f)
                    .height(48.dp),
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color.White.copy(alpha = 0.15f),
                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                ),
            ) {
                Text(
                    text = stringResource(R.string.spotify_import_start),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

/** Step 3: Animated matching progress view. */
@Composable
private fun MatchingStepView(
    status: String,
    completed: Int,
    total: Int,
    onCancel: () -> Unit,
) {
    val progress = if (total > 0) (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "importProgress")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Minimalist white circular loader with Spotify logo
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(54.dp),
                color = Color.White,
                strokeWidth = 2.5.dp,
            )
            Icon(
                painter = painterResource(Res.drawable.spotify_logo),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.spotify_import_step_matching_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
            color = Color.White,
        )

        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = Color.White.copy(alpha = 0.70f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        // Smooth progress bar
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(percent = 50)),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.15f),
        )

        if (total > 0) {
            val percent = (animatedProgress * 100).toInt()
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.spotify_import_cancel),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.60f),
            )
        }
    }
}

/** Step 4: Summary view for unmatched songs if any were skipped. */
@Composable
private fun UnmatchedStepView(
    missed: List<SpotifyImportTrack>,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(46.dp),
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.spotify_import_complete),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
            color = Color.White,
        )

        Text(
            text = stringResource(R.string.spotify_import_unmatched, missed.size),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
            color = Color.White.copy(alpha = 0.70f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            missed.forEach { track ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.45f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (track.artist.isBlank()) track.title else "${track.title} · ${track.artist}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
        ) {
            Text(
                text = stringResource(R.string.spotify_import_done_button),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
