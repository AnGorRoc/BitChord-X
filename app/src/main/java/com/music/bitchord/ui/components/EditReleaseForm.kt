package com.music.bitchord.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.music.bitchord.R
import com.music.bitchord.data.artwork.CustomArtworkStore
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.launch
import java.io.File

/**
 * Edit form for albums and playlists: integrates both cover customization
 * and title editing in a clean, unified modal.
 */
@Composable
fun EditReleaseForm(
    target: BrowseTarget,
    onBack: () -> Unit,
    onRename: ((String) -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    var name by remember(target.title) { mutableStateOf(target.title) }
    val artistName = remember(target.songs) { target.songs.firstOrNull()?.artist.orEmpty() }

    val customArtUpdates by CustomArtworkStore.updates.collectAsStateWithLifecycle()
    val customCoverPath = remember(target.browseId, customArtUpdates) {
        target.browseId?.let { CustomArtworkStore.getCoverPath(context, it) }
    }
    val customLogoPath = remember(target.browseId, target.title, artistName, customArtUpdates) {
        CustomArtworkStore.getLogoPath(context, target.browseId, target.title, artistName)
    }

    var cropperUri by remember { mutableStateOf<Uri?>(null) }
    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            cropperUri = uri
        }
    }

    val logoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                CustomArtworkStore.saveLogo(context, target.browseId, target.title, artistName, uri)
            }
        }
    }

    cropperUri?.let { uri ->
        SquareImageCropperDialog(
            imageUri = uri,
            onDismiss = { cropperUri = null },
            onCropConfirmed = { cropped ->
                scope.launch {
                    target.browseId?.let { id ->
                        CustomArtworkStore.saveCover(context, id, cropped)
                    }
                    cropperUri = null
                }
            },
        )
    }

    val submit: () -> Unit = {
        focusManager.clearFocus()
        if (onRename != null && name.isNotBlank() && name.trim() != target.title) {
            onRename(name.trim())
        }
        if (onDone != null) {
            onDone()
        } else {
            onBack()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding(),
    ) {
        // Top header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    SfIcons.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = if (target.type == BrowseType.ALBUM) {
                    stringResource(R.string.edit)
                } else {
                    stringResource(R.string.edit_playlist)
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = submit,
                enabled = name.isNotBlank(),
            ) {
                Text(
                    text = stringResource(R.string.done),
                    fontWeight = FontWeight.SemiBold,
                    color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        Spacer(Modifier.height(20.dp))

        // Cover section
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .shadow(10.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                    .clickable {
                        coverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val coverModel = if (customCoverPath != null) {
                    ImageRequest.Builder(context)
                        .data(File(customCoverPath))
                        .memoryCacheKey("$customCoverPath-$customArtUpdates")
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build()
                } else {
                    target.thumbnailUrl
                }

                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = target.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }

                // Subdued bottom badge on cover
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = SfIcons.Pencil,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.change_cover),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (customCoverPath != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            target.browseId?.let { id ->
                                CustomArtworkStore.removeCover(context, id)
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.restore_original_cover),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Title Logo section (compact card)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
        ) {
            Text(
                text = stringResource(R.string.title_logo),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        logoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (customLogoPath != null) {
                    // Dark compact preview container for the transparent logo
                    Box(
                        modifier = Modifier
                            .height(42.dp)
                            .width(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val logoModel = remember(customLogoPath, customArtUpdates) {
                            ImageRequest.Builder(context)
                                .data(File(customLogoPath))
                                .memoryCacheKey("$customLogoPath-$customArtUpdates")
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .build()
                        }
                        AsyncImage(
                            model = logoModel,
                            contentDescription = target.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Text(
                        text = stringResource(R.string.change_logo),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )

                    // Discrete close button to restore/remove custom logo
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                            .clickable {
                                scope.launch {
                                    CustomArtworkStore.removeLogo(context, target.browseId, target.title, artistName)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SfIcons.Close,
                            contentDescription = stringResource(R.string.restore_original_title),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                } else {
                    Icon(
                        imageVector = SfIcons.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )

                    Spacer(Modifier.width(12.dp))

                    Text(
                        text = stringResource(R.string.select_logo),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )

                    Icon(
                        imageVector = SfIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Name section
        if (onRename != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
            ) {
                Text(
                    text = stringResource(R.string.name_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                        .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                    )

                    if (name.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f))
                                .clickable { name = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = submit,
            enabled = name.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.done),
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}
