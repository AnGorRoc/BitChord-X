package com.music.bitchord.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * Dropdown menu matching the BitChord desktop card aesthetic:
 * acrylic dark fill, fine edge border, rounded corners and compact height.
 */
@Composable
internal fun DesktopDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier = modifier.widthIn(min = 180.dp, max = 280.dp),
        shape = shape,
        containerColor = DesktopCardFill,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(0.5.dp, DesktopCardEdge),
        content = content,
    )
}

/**
 * A single menu item with desktop-proportional compact height,
 * rounded hover pill highlight, and optional icons.
 */
@Composable
internal fun DesktopDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentColor: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(7.dp)
    val itemColor = contentColor ?: if (enabled) Color.White else DesktopSecondary
    val iconColor = contentColor ?: if (enabled) Color.White.copy(alpha = 0.85f) else DesktopSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (hovered && enabled) DesktopRowHover else Color.Transparent)
            .hoverable(interaction, enabled = enabled)
            .clickable(
                enabled = enabled,
                onClick = onClick,
                interactionSource = interaction,
                indication = null,
            )
            .heightIn(min = 32.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides iconColor,
                ) {
                    leadingIcon()
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides itemColor,
                LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
            ) {
                text()
            }
        }
        if (trailingIcon != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides iconColor,
                ) {
                    trailingIcon()
                }
            }
        } else if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                BitChordIcons.Check,
                contentDescription = null,
                tint = DesktopAccent,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Text-only or label-convenience overload for [DesktopDropdownMenuItem].
 */
@Composable
internal fun DesktopDropdownMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentColor: Color? = null,
    trailingText: String? = null,
) {
    DesktopDropdownMenuItem(
        text = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = icon?.let {
            {
                Icon(
                    it,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        trailingIcon = if (!trailingText.isNullOrBlank()) {
            {
                Text(
                    trailingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopSecondary,
                )
            }
        } else null,
        enabled = enabled,
        selected = selected,
        contentColor = contentColor,
    )
}

/**
 * Hairline rule separating groups within a [DesktopDropdownMenu].
 */
@Composable
internal fun DesktopDropdownMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 4.dp, horizontal = 4.dp),
        thickness = 0.5.dp,
        color = DesktopCardEdge,
    )
}
