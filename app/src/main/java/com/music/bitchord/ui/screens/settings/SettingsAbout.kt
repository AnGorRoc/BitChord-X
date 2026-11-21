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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val CardShape = RoundedCornerShape(22.dp)

/**
 * Settings' About page, all on one page: the BitChord logo as the README
 * shows it, with the version and a check for updates under it; the team,
 * and our repository under them; then the credit to the original project,
 * and its repository under that.
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AboutHero(version)

        AboutHeader(stringResource(R.string.about_app_team))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TEAM.forEach { member ->
                TeamCard(member, Modifier.weight(1f))
            }
        }
        RepoCard(
            title = stringResource(R.string.about_app_our_repo),
            url = REPO_URL,
        )

        AboutHeader(stringResource(R.string.about_app_credits))
        OriginalCreditCard()
        RepoCard(
            title = stringResource(R.string.about_app_original_repo),
            url = ORIGINAL_REPO_URL,
        )

        Text(
            text = "~YouTube Music & Listen Together Backend",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 8.dp),
        )
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

@Composable
private fun AboutHero(version: String) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The logo as the README has it: the original icon, and the wordmark
        // beside it. The wordmark is white in the file; here it takes the
        // text colour, so it reads on a light page as well as a dark one.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.about_logo_icon),
                contentDescription = null,
                modifier = Modifier.size(LOGO_ICON),
            )
            Spacer(Modifier.width(LOGO_ICON * LOGO_GAP))
            Image(
                painter = painterResource(R.drawable.about_logo_wordmark),
                contentDescription = "BitChord",
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.height(LOGO_ICON * LOGO_WORDMARK_HEIGHT),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.about_app_version, version),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .background(cardSurface())
                .padding(horizontal = 14.dp, vertical = 5.dp),
        )
        Spacer(Modifier.height(16.dp))
        val upToDate = stringResource(R.string.about_app_up_to_date)
        Text(
            text = stringResource(if (checking) R.string.about_app_checking else R.string.about_app_check_updates),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
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
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AboutHeader(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 12.dp),
    )
}

@Composable
private fun TeamCard(member: TeamMember, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(cardSurface())
            .clickable { uriHandler.openUri("https://github.com/${member.github}") }
            .padding(vertical = 18.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(member.avatar, 76)
        Spacer(Modifier.height(10.dp))
        Text(
            text = member.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "@${member.github}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OriginalCreditCard() {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(cardSurface())
            .clickable { uriHandler.openUri("https://github.com/$ORIGINAL_AUTHOR") }
            .padding(vertical = 18.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(R.drawable.about_avatar_kushagrasinghx, 64)
        Spacer(Modifier.height(10.dp))
        Text(
            text = ORIGINAL_AUTHOR,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.about_app_original_role),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.about_app_original_thanks),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RepoCard(title: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(cardSurface())
            .clickable { uriHandler.openUri(url) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "</>",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.background,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = url.removePrefix("https://"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Chevron()
    }
}

/** Someone's GitHub picture, round, with a faint rim. */
@Composable
private fun Avatar(@DrawableRes picture: Int, sizeDp: Int) {
    Image(
        painter = painterResource(picture),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.16f), CircleShape),
    )
}

/** The logo's icon, and the wordmark's height and the gap to it as fractions of it, as in Logo.png. */
private val LOGO_ICON = 84.dp
private const val LOGO_WORDMARK_HEIGHT = 210f / 512f
private const val LOGO_GAP = 100f / 512f
