package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.theme.cardSurface
import kotlinx.coroutines.launch
import java.util.Locale

/** Who makes BitChord X, as the About page lists them, with the picture GitHub shows for them. */
private data class TeamMember(val name: String, val github: String, @param:DrawableRes val avatar: Int)

private val TEAM = listOf(
    TeamMember("AnGor", "AnGorRoc", R.drawable.about_avatar_angor),
    TeamMember("fvkinGordo", "fvkinGordo", R.drawable.about_avatar_fvkingordo),
)

private const val REPO_URL = "https://github.com/AnGorRoc/BitChord-X"
private const val ORIGINAL_AUTHOR = "kushagrasinghx"
private const val ORIGINAL_REPO_URL = "https://github.com/kushagrasinghx/BitChord"

private val CardShape = RoundedCornerShape(24.dp)

/** The red of the original icon, top to bottom, as in Logo.png. */
private val BRAND_RED = listOf(Color(0xFFFF6079), Color(0xFFFB2442))

/**
 * Settings' About page, all on one page: the logo large at the top, with the
 * version and a check for updates; the team, each with a
 * way to their GitHub, and one button to our repository; then the credit to
 * the original project with its own button; and a small footer.
 *
 * The logo is always the original, whichever app icon the person picked.
 * The faces are each person's GitHub picture, kept in the app (taken from
 * GitHub on 2026-11-21) so opening the page asks nothing of the network.
 */
@Composable
internal fun SettingsAboutPage(version: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AboutHero(version)

        AboutHeader(stringResource(R.string.about_app_team))
        AboutCard {
            TEAM.forEachIndexed { index, member ->
                if (index > 0) CardDivider()
                PersonRow(
                    picture = member.avatar,
                    name = member.name,
                    detail = "@${member.github}",
                    profileUrl = "https://github.com/${member.github}",
                )
            }
            Spacer(Modifier.height(8.dp))
            GitHubButton(
                label = stringResource(R.string.about_app_our_repo),
                url = REPO_URL,
                filled = true,
            )
        }

        // The credit in one row, so the whole page fits a screen: the author,
        // what they made, and a tap on the row to the original project.
        AboutHeader(stringResource(R.string.about_app_credits))
        AboutCard(vertical = 4.dp) {
            PersonRow(
                picture = R.drawable.about_avatar_kushagrasinghx,
                name = ORIGINAL_AUTHOR,
                detail = stringResource(R.string.about_app_original_role),
                profileUrl = ORIGINAL_REPO_URL,
            )
        }

        AboutFooter()
    }
}

/** The settings main page's last card, into the About page. */
@Composable
internal fun SettingsAboutRow(version: String, onClick: () -> Unit) {
    SettingsGroup {
        SettingsRow(
            icon = SfIcons.Info,
            title = stringResource(R.string.about_app),
            subtitle = stringResource(R.string.about_app_summary, version),
            onClick = onClick,
        )
    }
}

/**
 * The header: the logo as the README shows it — the icon with the name beside
 * it — standing where any Settings page has its title, the name in the text
 * colour so it follows the theme; under it the version and a check for
 * updates in the icon's red.
 */
@Composable
private fun AboutHero(version: String) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.about_logo_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(TITLE_ICON)
                    .shadow(
                        elevation = 14.dp,
                        shape = RoundedCornerShape(TITLE_ICON * ICON_CORNER),
                        clip = false,
                        spotColor = BRAND_RED[1],
                        ambientColor = BRAND_RED[1],
                    ),
            )
            Spacer(Modifier.width(TITLE_ICON * LOGO_GAP))
            Image(
                painter = painterResource(R.drawable.about_logo_wordmark),
                contentDescription = "BitChord",
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.height(TITLE_ICON * LOGO_WORDMARK_HEIGHT),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.about_app_version, version),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .background(cardSurface())
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        Spacer(Modifier.width(10.dp))
        val upToDate = stringResource(R.string.about_app_up_to_date)
        Text(
            text = stringResource(if (checking) R.string.about_app_checking else R.string.about_app_check_updates),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier
                .clip(CircleShape)
                .background(Brush.verticalGradient(BRAND_RED))
                .clickable(enabled = !checking) {
                    checking = true
                    scope.launch {
                        AppUpdateChecker.check()
                        checking = false
                        val update = AppUpdateChecker.available.value
                        if (update != null) {
                            uriHandler.openUri(update.releaseUrl)
                        } else {
                            Toast.makeText(context, upToDate, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
        }
    }
}

@Composable
private fun AboutHeader(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp),
    )
}

/** One of the page's cards: the settings card surface, rounded, padded. */
@Composable
private fun AboutCard(vertical: Dp = 10.dp, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(cardSurface())
            .padding(horizontal = 14.dp, vertical = vertical),
    ) {
        content()
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = PERSON_AVATAR + 14.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** A person: their picture, name and a line under it, and their GitHub a tap away. */
@Composable
private fun PersonRow(@DrawableRes picture: Int, name: String, detail: String, profileUrl: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { uriHandler.openUri(profileUrl) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(picture, PERSON_AVATAR)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        // A small mark of where the tap goes.
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = GitHubMark,
                contentDescription = "GitHub",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A wide button to a repository, with the GitHub mark: filled for ours, outlined for the original. */
@Composable
private fun GitHubButton(label: String, url: String, filled: Boolean) {
    val uriHandler = LocalUriHandler.current
    val ink = MaterialTheme.colorScheme.onBackground
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(ink)
                } else {
                    Modifier.border(1.dp, ink.copy(alpha = 0.25f), shape)
                },
            )
            .clickable { uriHandler.openUri(url) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val content = if (filled) MaterialTheme.colorScheme.background else ink
        Icon(
            imageVector = GitHubMark,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The page's foot: who made it, the licence, and the backend line that used to sit under Settings. */
@Composable
private fun AboutFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.about_app_made_by, TEAM.joinToString(" & ") { it.name }) + " · GPLv3",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "~YouTube Music & Listen Together Backend",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Someone's GitHub picture, round, with a faint rim. */
@Composable
private fun Avatar(@DrawableRes picture: Int, size: Dp) {
    Image(
        painter = painterResource(picture),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f), CircleShape),
    )
}

/** The GitHub mark, from Primer Octicons (MIT licence), on its 16-unit grid. */
private val GitHubMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "GitHubMark",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).addPath(
        pathData = PathParser().parsePathString(
            "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 " +
                "0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13" +
                "-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66" +
                ".07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15" +
                "-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 " +
                "1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 " +
                "1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 " +
                "1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

/**
 * The title logo's icon; its corner, the name's height and the gap between
 * them as fractions of it, as in Logo.png.
 */
private val TITLE_ICON = 72.dp
private const val ICON_CORNER = 170f / 512f
private const val LOGO_WORDMARK_HEIGHT = 210f / 512f
private const val LOGO_GAP = 100f / 512f

/** A person's picture in the team and credits cards. */
private val PERSON_AVATAR = 46.dp
