package com.music.bitchord.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.DjSetCategory
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.dj_set_cat_club
import com.music.bitchord.sharedui.resources.dj_set_cat_cumbia_rkt
import com.music.bitchord.sharedui.resources.dj_set_cat_electronic
import com.music.bitchord.sharedui.resources.dj_set_cat_featured
import com.music.bitchord.sharedui.resources.dj_set_cat_reggaeton
import com.music.bitchord.sharedui.resources.dj_sets_badge
import com.music.bitchord.sharedui.resources.dj_sets_subtitle
import com.music.bitchord.sharedui.resources.dj_sets_title
import com.music.bitchord.sharedui.resources.retry
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ShimmerBox
import org.jetbrains.compose.resources.stringResource

/*
 * Explore's continuous DJ sets & enganchados section: category chips and
 * 16:9 widescreen cards showing long-form continuous mixes with runtime badges.
 */

@Composable
private fun DjSetCategory.label(): String = stringResource(
    when (this) {
        DjSetCategory.FEATURED -> Res.string.dj_set_cat_featured
        DjSetCategory.ELECTRONIC -> Res.string.dj_set_cat_electronic
        DjSetCategory.CUMBIA_RKT -> Res.string.dj_set_cat_cumbia_rkt
        DjSetCategory.REGGAETON -> Res.string.dj_set_cat_reggaeton
        DjSetCategory.CLUB -> Res.string.dj_set_cat_club
    },
)

internal fun LazyListScope.djSetsSection(
    state: UiState<List<Song>>,
    category: DjSetCategory,
    onCategoryChange: (DjSetCategory) -> Unit,
    onSetClick: (Song) -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "djsets-header") { DjSetsHeader() }
    item(key = "djsets-categories") { DjSetsCategoryChips(category, onCategoryChange) }
    item(key = "djsets-row") {
        when (state) {
            UiState.Loading -> LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = false,
            ) {
                items(3) {
                    ShimmerBox(
                        Modifier
                            .width(DJ_SET_CARD_WIDTH)
                            .aspectRatio(16f / 9f),
                        RoundedCornerShape(20.dp),
                    )
                }
            }
            is UiState.Error -> MessageState(
                message = state.message,
                actionLabel = stringResource(Res.string.retry),
                onAction = onRetry,
            )
            is UiState.Success -> LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.data, key = { it.videoId }) { setSong ->
                    DjSetCard(setSong, onClick = { onSetClick(setSong) })
                }
            }
        }
    }
}

private val DJ_SET_CARD_WIDTH = 264.dp

@Composable
private fun DjSetsHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 24.dp, bottom = 10.dp),
    ) {
        Text(
            text = stringResource(Res.string.dj_sets_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.dj_sets_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DjSetsCategoryChips(selected: DjSetCategory, onSelect: (DjSetCategory) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 14.dp),
    ) {
        items(DjSetCategory.entries, key = { it.id }) { category ->
            val active = category == selected
            val ink by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "djCatInk",
            )
            val plate by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "djCatPlate",
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(plate)
                    .clickable { onSelect(category) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = category.label(),
                    color = ink,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun DjSetCard(setSong: Song, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.width(DJ_SET_CARD_WIDTH)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
        ) {
            setSong.thumbnailUrl?.let { art ->
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = stringResource(Res.string.dj_sets_badge),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
            }
            setSong.durationText?.takeIf { it.isNotBlank() }?.let { duration ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = duration,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = setSong.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = setSong.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
